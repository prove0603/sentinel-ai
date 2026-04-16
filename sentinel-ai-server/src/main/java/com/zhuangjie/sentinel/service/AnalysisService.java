package com.zhuangjie.sentinel.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer;
import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer.AiAnalysisDetail;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.knowledge.KnowledgeContextBuilder;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import com.zhuangjie.sentinel.pojo.enums.SqlSourceType;
import com.zhuangjie.sentinel.pojo.vo.AnalysisDetailVo;
import com.zhuangjie.sentinel.scanner.SqlNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final SqlAnalysisDbService sqlAnalysisDbService;
    private final SqlRecordDbService sqlRecordDbService;
    private final ProjectService projectService;

    @Autowired(required = false)
    private KnowledgeContextBuilder knowledgeContextBuilder;

    @Autowired(required = false)
    private AiSqlAnalyzer aiSqlAnalyzer;

    public Page<SqlAnalysis> pageByBatch(Long batchId, int current, int size) {
        return sqlAnalysisDbService.page(
                new Page<>(current, size),
                new LambdaQueryWrapper<SqlAnalysis>()
                        .eq(SqlAnalysis::getScanBatchId, batchId)
                        .orderByAsc(SqlAnalysis::getFinalRiskLevel));
    }

    public Page<SqlAnalysis> pageByCondition(Long projectId, String riskLevel, String handleStatus,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              int current, int size) {
        LambdaQueryWrapper<SqlAnalysis> wrapper = new LambdaQueryWrapper<>();

        if (projectId != null) {
            List<Long> recordIds = sqlRecordDbService.list(
                    new LambdaQueryWrapper<SqlRecord>()
                            .select(SqlRecord::getId)
                            .eq(SqlRecord::getProjectId, projectId)
                            .eq(SqlRecord::getStatus, 1)
            ).stream().map(SqlRecord::getId).toList();

            if (recordIds.isEmpty()) {
                return new Page<>(current, size);
            }
            wrapper.in(SqlAnalysis::getSqlRecordId, recordIds);
        }

        wrapper.eq(riskLevel != null && !riskLevel.isBlank(), SqlAnalysis::getFinalRiskLevel, riskLevel)
                .eq(handleStatus != null && !handleStatus.isBlank(), SqlAnalysis::getHandleStatus, handleStatus)
                .ge(startTime != null, SqlAnalysis::getCreateTime, startTime)
                .le(endTime != null, SqlAnalysis::getCreateTime, endTime)
                .orderByDesc(SqlAnalysis::getCreateTime);

        return sqlAnalysisDbService.page(new Page<>(current, size), wrapper);
    }

    public AnalysisDetailVo getDetail(Long id) {
        SqlAnalysis analysis = sqlAnalysisDbService.getById(id);
        if (analysis == null) {
            return null;
        }

        AnalysisDetailVo vo = new AnalysisDetailVo();
        vo.setAnalysis(analysis);

        SqlRecord record = sqlRecordDbService.getById(analysis.getSqlRecordId());
        if (record != null) {
            vo.setSqlText(record.getSqlText());
            vo.setSqlNormalized(record.getSqlNormalized());
            vo.setSqlType(record.getSqlType());
            vo.setSourceType(record.getSourceType());
            vo.setSourceFile(record.getSourceFile());
            vo.setSourceLocation(record.getSourceLocation());
            vo.setOwner(record.getOwner());

            ProjectConfig project = projectService.getById(record.getProjectId());
            if (project != null) {
                vo.setProjectName(project.getProjectName());

                if (knowledgeContextBuilder != null && knowledgeContextBuilder.isAvailable()
                        && record.getSqlNormalized() != null) {
                    try {
                        String ctx = knowledgeContextBuilder.buildContext(record.getSqlNormalized());
                        vo.setTableMetaContext(ctx.isBlank() ? null : ctx);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return vo;
    }

    public boolean updateHandleStatus(Long id, String status, String note, String handler) {
        SqlAnalysis analysis = new SqlAnalysis();
        analysis.setId(id);
        analysis.setHandleStatus(status);
        analysis.setHandleNote(note);
        analysis.setHandler(handler);
        analysis.setHandleTime(LocalDateTime.now());
        return sqlAnalysisDbService.updateById(analysis);
    }

    public AnalysisDetailVo analyzeByRecordId(Long sqlRecordId) {
        if (aiSqlAnalyzer == null || !aiSqlAnalyzer.isAvailable()) {
            throw new IllegalStateException("AI 分析未启用，请检查配置");
        }

        SqlRecord record = sqlRecordDbService.getById(sqlRecordId);
        if (record == null) {
            throw new IllegalArgumentException("SQL 记录不存在: " + sqlRecordId);
        }

        ProjectConfig project = projectService.getById(record.getProjectId());
        String projectName = project != null ? project.getProjectName() : null;

        ScannedSql scannedSql = new ScannedSql(
                record.getSqlText(),
                record.getSqlNormalized(),
                record.getSqlType(),
                SqlSourceType.fromCode(record.getSourceType()),
                record.getSourceFile(),
                record.getSourceLocation()
        );

        String sqlHash = SqlNormalizer.hash(record.getSqlNormalized());
        AiAnalysisDetail aiDetail = aiSqlAnalyzer.analyze(sqlHash, scannedSql, projectName);
        if (aiDetail == null) {
            throw new RuntimeException("AI 分析调用失败，请检查日志");
        }

        SqlRiskAssessment ai = aiDetail.assessment();
        SqlAnalysis analysis = new SqlAnalysis();
        analysis.setSqlRecordId(record.getId());
        analysis.setScanBatchId(record.getLastScanId());
        analysis.setAiRiskLevel(ai.riskLevel());
        analysis.setAiAnalysis(ai.explanation());
        analysis.setAiIndexSuggestion(JSONUtil.toJsonStr(ai.indexSuggestions()));
        analysis.setAiRewriteSuggestion(JSONUtil.toJsonStr(ai.rewriteSuggestions()));
        analysis.setAiEstimatedScanRows(ai.estimatedScanRows());
        analysis.setAiEstimatedExecTimeMs(ai.estimatedExecTimeMs());
        analysis.setAiModel(aiDetail.model());
        analysis.setAiTokensUsed(aiDetail.tokensUsed());
        analysis.setFinalRiskLevel(ai.riskLevel());
        analysis.setHandleStatus("ANALYZED");
        analysis.setCreateTime(LocalDateTime.now());
        sqlAnalysisDbService.save(analysis);

        log.info("Manual AI analysis completed: sqlRecordId={}, riskLevel={}, model={}, tokens={}",
                sqlRecordId, ai.riskLevel(), aiDetail.model(), aiDetail.tokensUsed());

        return getDetail(analysis.getId());
    }
}
