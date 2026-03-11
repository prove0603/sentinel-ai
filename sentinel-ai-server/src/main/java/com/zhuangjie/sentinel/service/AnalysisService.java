package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.knowledge.KnowledgeContextBuilder;
import com.zhuangjie.sentinel.pojo.vo.AnalysisDetailVo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

            ProjectConfig project = projectService.getById(record.getProjectId());
            if (project != null) {
                vo.setProjectName(project.getProjectName());

                if (knowledgeContextBuilder != null && knowledgeContextBuilder.isAvailable()
                        && record.getSqlNormalized() != null) {
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
        analysis.setHandleTime(LocalDateTime.now());
        return sqlAnalysisDbService.updateById(analysis);
    }
}
