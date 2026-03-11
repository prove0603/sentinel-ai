package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.knowledge.KnowledgeContextBuilder;
import com.zhuangjie.sentinel.pojo.vo.AnalysisDetailVo;
import com.zhuangjie.sentinel.service.ProjectService;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final SqlAnalysisDbService sqlAnalysisDbService;
    private final SqlRecordDbService sqlRecordDbService;
    private final ProjectService projectService;

    @Autowired(required = false)
    private KnowledgeContextBuilder knowledgeContextBuilder;

    public Page<SqlAnalysis> pageByBatch(Long batchId, int current, int size) {
        return sqlAnalysisDbService.page(
                new Page<>(current, size),
                new LambdaQueryWrapper<SqlAnalysis>()
                        .eq(SqlAnalysis::getScanBatchId, batchId)
                        .orderByAsc(SqlAnalysis::getFinalRiskLevel));
    }

    public Page<SqlAnalysis> pageByRisk(String riskLevel, String handleStatus, int current, int size) {
        return sqlAnalysisDbService.page(
                new Page<>(current, size),
                new LambdaQueryWrapper<SqlAnalysis>()
                        .eq(riskLevel != null && !riskLevel.isBlank(), SqlAnalysis::getFinalRiskLevel, riskLevel)
                        .eq(handleStatus != null && !handleStatus.isBlank(), SqlAnalysis::getHandleStatus, handleStatus)
                        .orderByAsc(SqlAnalysis::getFinalRiskLevel)
                        .orderByDesc(SqlAnalysis::getCreateTime));
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

            if (knowledgeContextBuilder != null && knowledgeContextBuilder.isAvailable()) {
                ProjectConfig project = projectService.getById(record.getProjectId());
                if (project != null && record.getSqlNormalized() != null) {
                    try {
                        String ctx = knowledgeContextBuilder.buildContext(
                                record.getSqlNormalized(), project.getProjectName());
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
        return sqlAnalysisDbService.updateById(analysis);
    }
}
