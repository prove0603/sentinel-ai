package com.zhuangjie.sentinel.service;

import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.ScanBatch;
import com.zhuangjie.sentinel.db.service.ScanBatchDbService;
import com.zhuangjie.sentinel.pojo.enums.ScanType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanService {

    private final ProjectService projectService;
    private final ScanBatchDbService scanBatchDbService;

    /**
     * Trigger a scan for a project. Runs asynchronously on a virtual thread.
     */
    @Async
    public CompletableFuture<ScanBatch> triggerScan(Long projectId) {
        ProjectConfig project = projectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }

        boolean isFullScan = project.getLastScanCommit() == null;
        ScanType scanType = isFullScan ? ScanType.FULL : ScanType.INCREMENTAL;

        ScanBatch batch = new ScanBatch();
        batch.setProjectId(projectId);
        batch.setScanType(scanType.getCode());
        batch.setStatus("RUNNING");
        batch.setCreateTime(LocalDateTime.now());
        scanBatchDbService.save(batch);

        long startTime = System.currentTimeMillis();

        try {
            // TODO P1: invoke scanner engine here
            log.info("Scan started: projectId={}, type={}, batchId={}", projectId, scanType, batch.getId());

            batch.setTotalSqlCount(0);
            batch.setNewSqlCount(0);
            batch.setChangedSqlCount(0);
            batch.setRemovedSqlCount(0);
            batch.setRiskSqlCount(0);
            batch.setStatus("COMPLETED");
        } catch (Exception e) {
            log.error("Scan failed: projectId={}, batchId={}", projectId, batch.getId(), e);
            batch.setStatus("FAILED");
        } finally {
            batch.setScanDurationMs(System.currentTimeMillis() - startTime);
            scanBatchDbService.updateById(batch);
        }

        return CompletableFuture.completedFuture(batch);
    }
}
