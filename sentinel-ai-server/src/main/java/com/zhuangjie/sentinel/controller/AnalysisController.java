package com.zhuangjie.sentinel.controller;

import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.pojo.vo.AnalysisDetailVo;
import com.zhuangjie.sentinel.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @GetMapping("/page")
    public Result<PageResult<SqlAnalysis>> page(
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String handleStatus,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(PageResult.of(analysisService.pageByRisk(riskLevel, handleStatus, current, size)));
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
}
