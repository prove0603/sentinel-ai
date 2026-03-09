package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final SqlAnalysisDbService sqlAnalysisDbService;

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
                        .eq(riskLevel != null, SqlAnalysis::getFinalRiskLevel, riskLevel)
                        .eq(handleStatus != null, SqlAnalysis::getHandleStatus, handleStatus)
                        .orderByAsc(SqlAnalysis::getFinalRiskLevel)
                        .orderByDesc(SqlAnalysis::getCreateTime));
    }

    public SqlAnalysis getDetail(Long id) {
        return sqlAnalysisDbService.getById(id);
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
