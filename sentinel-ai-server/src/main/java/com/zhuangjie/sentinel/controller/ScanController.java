package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ScanBatch;
import com.zhuangjie.sentinel.db.service.ScanBatchDbService;
import com.zhuangjie.sentinel.service.ScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;
    private final ScanBatchDbService scanBatchDbService;

    @PostMapping("/trigger/{projectId}")
    public Result<String> triggerScan(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "false") boolean forceFullScan) {
        scanService.triggerScan(projectId, forceFullScan);
        return Result.ok("Scan triggered" + (forceFullScan ? " (force full)" : ""));
    }

    @GetMapping("/history")
    public Result<PageResult<ScanBatch>> history(
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        var page = scanBatchDbService.page(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size),
                new LambdaQueryWrapper<ScanBatch>()
                        .eq(projectId != null, ScanBatch::getProjectId, projectId)
                        .orderByDesc(ScanBatch::getCreateTime));
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/batch/{id}")
    public Result<ScanBatch> getBatch(@PathVariable Long id) {
        return Result.ok(scanBatchDbService.getById(id));
    }
}
