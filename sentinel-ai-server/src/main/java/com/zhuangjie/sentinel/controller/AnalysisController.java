package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.pojo.vo.AnalysisDetailVo;
import com.zhuangjie.sentinel.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final SqlRecordDbService sqlRecordDbService;

    @GetMapping("/page")
    public Result<PageResult<SqlAnalysis>> page(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String handleStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        Page<SqlAnalysis> pageResult = analysisService.pageByCondition(
                projectId, riskLevel, handleStatus, startTime, endTime, current, size);
        fillOwners(pageResult.getRecords());
        return Result.ok(PageResult.of(pageResult));
    }

    @GetMapping("/batch/{batchId}")
    public Result<PageResult<SqlAnalysis>> pageByBatch(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(PageResult.of(analysisService.pageByBatch(batchId, current, size)));
    }

    @GetMapping("/{id}")
    public Result<AnalysisDetailVo> detail(@PathVariable Long id) {
        return Result.ok(analysisService.getDetail(id));
    }

    @PutMapping("/{id}/handle")
    public Result<Void> handle(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) String handler) {
        analysisService.updateHandleStatus(id, status, note, handler);
        return Result.ok();
    }

    @PostMapping("/reanalyze/{sqlRecordId}")
    public Result<AnalysisDetailVo> reanalyze(@PathVariable Long sqlRecordId) {
        return Result.ok(analysisService.analyzeByRecordId(sqlRecordId));
    }

    private void fillOwners(List<SqlAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) return;
        List<Long> recordIds = analyses.stream()
                .map(SqlAnalysis::getSqlRecordId)
                .distinct()
                .toList();
        Map<Long, String> ownerMap = sqlRecordDbService.listByIds(recordIds).stream()
                .collect(Collectors.toMap(SqlRecord::getId, r -> r.getOwner() != null ? r.getOwner() : "-"));
        analyses.forEach(a -> a.setOwner(ownerMap.getOrDefault(a.getSqlRecordId(), "-")));
    }
}
