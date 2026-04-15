package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.knowledge.TableNameExtractor;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import com.zhuangjie.sentinel.pojo.enums.SqlSourceType;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * DDL 变更感知服务。
 * <p>
 * 当表结构 DDL 发生变更时，遍历所有活跃的 SQL 记录，找出引用了变更表的 SQL，
 * 异步触发 AI 重新分析。确保 DDL 变更后，风险评估结果能及时更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DdlChangeService {

    private final SqlRecordDbService sqlRecordDbService;
    private final SqlAnalysisDbService sqlAnalysisDbService;
    private final AiSqlAnalyzer aiSqlAnalyzer;

    @Autowired(required = false)
    private TableNameExtractor tableNameExtractor;

    /**
     * 异步重新分析所有引用了变更表的 SQL 记录。
     *
     * @param changedTables DDL 发生变更的表名列表
     * @param projectName   项目名称（用于 AI 上下文构建）
     */
    @Async
    public void reAnalyzeAffectedSqls(List<String> changedTables, String projectName) {
        if (changedTables == null || changedTables.isEmpty()) {
            return;
        }
        if (tableNameExtractor == null || !aiSqlAnalyzer.isAvailable()) {
            log.info("Re-analysis skipped: tableNameExtractor={}, aiAvailable={}",
                    tableNameExtractor != null, aiSqlAnalyzer.isAvailable());
            return;
        }

        log.info("Starting re-analysis for {} changed tables in project {}", changedTables.size(), projectName);

        Set<String> changedSet = Set.copyOf(changedTables.stream()
                .map(String::toLowerCase)
                .toList());

        List<SqlRecord> activeRecords = sqlRecordDbService.list(
                new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getStatus, 1));

        int reAnalyzed = 0;
        for (SqlRecord record : activeRecords) {
            if (record.getSqlNormalized() == null) continue;

            try {
                Set<String> referencedTables = tableNameExtractor.extract(record.getSqlNormalized());
                boolean affected = referencedTables.stream()
                        .map(String::toLowerCase)
                        .anyMatch(changedSet::contains);

                if (!affected) continue;

                ScannedSql scannedSql = new ScannedSql(
                        record.getSqlText(),
                        record.getSqlNormalized(),
                        record.getSqlType(),
                        SqlSourceType.fromCode(record.getSourceType()),
                        record.getSourceFile(),
                        record.getSourceLocation()
                );

                AiSqlAnalyzer.AiAnalysisDetail detail = aiSqlAnalyzer.analyze(
                        record.getSqlHash() + "_reanalyze", scannedSql, projectName);

                if (detail != null) {
                    SqlRiskAssessment ai = detail.assessment();

                    SqlAnalysis analysis = new SqlAnalysis();
                    analysis.setSqlRecordId(record.getId());
                    analysis.setScanBatchId(0L);
                    analysis.setAiRiskLevel(ai.riskLevel());
                    analysis.setAiAnalysis(ai.explanation());
                    analysis.setAiIndexSuggestion(JSONUtil.toJsonStr(ai.indexSuggestions()));
                    analysis.setAiRewriteSuggestion(JSONUtil.toJsonStr(ai.rewriteSuggestions()));
                    analysis.setAiEstimatedScanRows(ai.estimatedScanRows());
                    analysis.setAiEstimatedExecTimeMs(ai.estimatedExecTimeMs());
                    analysis.setAiModel(detail.model());
                    analysis.setAiTokensUsed(detail.tokensUsed());
                    analysis.setFinalRiskLevel(ai.riskLevel());
                    analysis.setHandleStatus("ANALYZED");
                    analysis.setHandleNote("DDL change re-analysis");
                    analysis.setCreateTime(LocalDateTime.now());
                    sqlAnalysisDbService.save(analysis);

                    reAnalyzed++;
                }
            } catch (Exception e) {
                log.warn("Failed to re-analyze SQL record {}: {}", record.getId(), e.getMessage());
            }
        }

        log.info("DDL change re-analysis completed: {} SQL records re-analyzed for {} changed tables",
                reAnalyzed, changedTables.size());
    }
}
