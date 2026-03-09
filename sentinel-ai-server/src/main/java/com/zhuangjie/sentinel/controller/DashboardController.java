package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.ScanBatch;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.ProjectConfigDbService;
import com.zhuangjie.sentinel.db.service.ScanBatchDbService;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final ProjectConfigDbService projectConfigDbService;
    private final SqlRecordDbService sqlRecordDbService;
    private final ScanBatchDbService scanBatchDbService;
    private final SqlAnalysisDbService sqlAnalysisDbService;

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> data = new HashMap<>();

        data.put("projectCount", projectConfigDbService.count(
                new LambdaQueryWrapper<ProjectConfig>().eq(ProjectConfig::getStatus, 1)));

        data.put("totalSqlCount", sqlRecordDbService.count(
                new LambdaQueryWrapper<SqlRecord>().eq(SqlRecord::getStatus, 1)));

        data.put("totalScanCount", scanBatchDbService.count());

        data.put("riskSqlCount", sqlAnalysisDbService.count(
                new LambdaQueryWrapper<SqlAnalysis>()
                        .in(SqlAnalysis::getFinalRiskLevel, "P0", "P1", "P2")));

        data.put("pendingCount", sqlAnalysisDbService.count(
                new LambdaQueryWrapper<SqlAnalysis>()
                        .eq(SqlAnalysis::getHandleStatus, "PENDING")
                        .in(SqlAnalysis::getFinalRiskLevel, "P0", "P1")));

        return Result.ok(data);
    }
}
