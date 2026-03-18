package com.zhuangjie.sentinel.controller;

import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.knowledge.DataPlatformClient;
import com.zhuangjie.sentinel.knowledge.RemoteDdlCollector;
import com.zhuangjie.sentinel.service.DdlChangeService;
import com.zhuangjie.sentinel.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/table-meta")
@RequiredArgsConstructor
public class TableMetaController {

    private static final String PLATFORM_NOT_CONFIGURED =
            "Remote data platform not configured. Set sentinel.data-platform.enabled=true in your config, " +
            "or manually maintain DDL files in the table-meta directory.";

    private final KnowledgeService knowledgeService;
    private final DdlChangeService ddlChangeService;

    @Autowired(required = false)
    private DataPlatformClient dataPlatformClient;

    @Autowired(required = false)
    private RemoteDdlCollector remoteDdlCollector;

    /**
     * Refreshes DDL for existing tables in table-meta/.
     * Queries current DDL from the remote platform and updates local files.
     *
     * @param limit max tables to process (-1 = no limit, default 10 for testing)
     */
    @PostMapping("/refresh-ddl")
    public Result<Map<String, Object>> refreshDdl(@RequestParam(defaultValue = "-1") int limit) {
        if (remoteDdlCollector == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            RemoteDdlCollector.RefreshResult result = remoteDdlCollector.refreshDdl(limit);
            return Result.ok(Map.of(
                    "total", result.total(),
                    "success", result.success(),
                    "failed", result.failed(),
                    "updated", result.updated(),
                    "updatedTables", result.updatedTables()
            ));
        } catch (Exception e) {
            return Result.fail("DDL refresh failed: " + e.getMessage());
        }
    }

    /**
     * Refreshes index statistics for existing tables in table-meta/.
     * Queries INFORMATION_SCHEMA.STATISTICS and appends cardinality/selectivity data to files.
     *
     * @param limit max tables to process (-1 = no limit, default 10 for testing)
     */
    @PostMapping("/refresh-index-stats")
    public Result<Map<String, Object>> refreshIndexStats(@RequestParam(defaultValue = "-1") int limit) {
        if (remoteDdlCollector == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            RemoteDdlCollector.RefreshResult result = remoteDdlCollector.refreshIndexStats(limit);
            return Result.ok(Map.of(
                    "total", result.total(),
                    "success", result.success(),
                    "failed", result.failed(),
                    "updated", result.updated(),
                    "updatedTables", result.updatedTables()
            ));
        } catch (Exception e) {
            return Result.fail("Index stats refresh failed: " + e.getMessage());
        }
    }

    @PostMapping("/auto-collect/{projectId}")
    public Result<Integer> autoCollect(@PathVariable Long projectId) {
        int count = knowledgeService.autoCollect(projectId);
        return Result.ok(count);
    }

    @PostMapping("/remote-collect")
    public Result<Map<String, Object>> remoteCollect(
            @RequestParam(defaultValue = "true") boolean reAnalyzeChanged) {
        if (remoteDdlCollector == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            RemoteDdlCollector.CollectionResult result = remoteDdlCollector.collectAll();
            if (reAnalyzeChanged && !result.changedTables().isEmpty()) {
                ddlChangeService.reAnalyzeAffectedSqls(result.changedTables(), null);
            }
            return Result.ok(Map.of(
                    "totalTables", result.totalTables(),
                    "successCount", result.successCount(),
                    "failedCount", result.failedCount(),
                    "changedCount", result.changedCount(),
                    "changedTables", result.changedTables()
            ));
        } catch (Exception e) {
            return Result.fail("Collection failed: " + e.getMessage());
        }
    }

    @GetMapping("/detect-changes")
    public Result<Map<String, Object>> detectChanges() {
        if (remoteDdlCollector == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            RemoteDdlCollector.DiffResult result = remoteDdlCollector.detectChanges();
            return Result.ok(Map.of(
                    "totalChecked", result.totalChecked(),
                    "changedTables", result.changedTables(),
                    "newTables", result.newTables(),
                    "deletedTables", result.deletedTables()
            ));
        } catch (Exception e) {
            return Result.fail("Change detection failed: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<List<String>> list() {
        return Result.ok(knowledgeService.listTables());
    }

    @GetMapping("/connection-test")
    public Result<Map<String, Object>> connectionTest() {
        if (dataPlatformClient == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            dataPlatformClient.login();
            DataPlatformClient.QueryResult result = dataPlatformClient.executeQuery(
                    "SELECT COUNT(1) AS cnt FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE()");
            String tableCount = result.rows().isEmpty() ? "0" : result.rows().get(0).get(0);
            return Result.ok(Map.of(
                    "status", "connected",
                    "tableCount", tableCount
            ));
        } catch (Exception e) {
            return Result.fail("Connection test failed: " + e.getMessage());
        }
    }
}
