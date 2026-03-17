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
 * Handles re-analysis of SQL statements affected by DDL changes.
 * When table structure changes, finds all SQL records referencing those tables
 * and triggers AI re-analysis.
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
     * Re-analyzes all SQL records that reference the given changed tables.
     * Runs asynchronously.
     *
     * @param changedTables list of table names whose DDL changed
     * @param projectName   the project name (for AI context)
     * @return number of SQL records re-analyzed
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
