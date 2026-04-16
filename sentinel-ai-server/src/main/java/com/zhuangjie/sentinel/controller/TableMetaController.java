package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.TableMeta;
import com.zhuangjie.sentinel.db.service.TableMetaDbService;
import com.zhuangjie.sentinel.knowledge.DataPlatformClient;
import com.zhuangjie.sentinel.knowledge.RemoteDdlCollector;
import com.zhuangjie.sentinel.knowledge.TableMetaCacheService;
import com.zhuangjie.sentinel.service.DdlChangeService;
import com.zhuangjie.sentinel.service.KnowledgeService;
import com.zhuangjie.sentinel.service.TableMetaService;
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
            "Remote data platform not configured. Set sentinel.data-platform.enabled=true in your config.";

    private final TableMetaDbService tableMetaDbService;
    private final TableMetaCacheService cacheService;
    private final TableMetaService tableMetaService;
    private final KnowledgeService knowledgeService;
    private final DdlChangeService ddlChangeService;

    @Autowired(required = false)
    private DataPlatformClient dataPlatformClient;

    @Autowired(required = false)
    private RemoteDdlCollector remoteDdlCollector;

    // ─── 分页查询 ──────────────────────────────────────────────

    @GetMapping("/page")
    public Result<PageResult<TableMeta>> page(
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        LambdaQueryWrapper<TableMeta> wrapper = new LambdaQueryWrapper<>();
        if (tableName != null && !tableName.isBlank()) {
            wrapper.like(TableMeta::getTableName, tableName);
        }
        wrapper.orderByAsc(TableMeta::getTableName);
        Page<TableMeta> page = tableMetaDbService.page(new Page<>(current, size), wrapper);
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<TableMeta> getById(@PathVariable Long id) {
        TableMeta meta = tableMetaDbService.getById(id);
        return meta != null ? Result.ok(meta) : Result.fail("记录不存在");
    }

    // ─── 手动新增/编辑/删除 ──────────────────────────────────────

    @PostMapping
    public Result<TableMeta> create(@RequestBody TableMeta tableMeta) {
        TableMeta existing = tableMetaDbService.getOne(
                new LambdaQueryWrapper<TableMeta>().eq(TableMeta::getTableName, tableMeta.getTableName()));
        if (existing != null) {
            return Result.fail("表 " + tableMeta.getTableName() + " 已存在");
        }
        tableMetaDbService.save(tableMeta);
        cacheService.put(tableMeta);
        return Result.ok(tableMeta);
    }

    @PutMapping("/{id}")
    public Result<TableMeta> update(@PathVariable Long id, @RequestBody TableMeta tableMeta) {
        TableMeta existing = tableMetaDbService.getById(id);
        if (existing == null) return Result.fail("记录不存在");

        existing.setDdlText(tableMeta.getDdlText());
        existing.setEstimatedRows(tableMeta.getEstimatedRows());
        existing.setIndexInfo(tableMeta.getIndexInfo());
        existing.setIndexStats(tableMeta.getIndexStats());
        if (tableMeta.getTableName() != null) {
            existing.setTableName(tableMeta.getTableName());
        }
        tableMetaDbService.updateById(existing);
        cacheService.put(existing);
        return Result.ok(existing);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        TableMeta existing = tableMetaDbService.getById(id);
        if (existing != null) {
            tableMetaDbService.removeById(id);
            cacheService.remove(existing.getTableName());
        }
        return Result.ok();
    }

    // ─── 从 DBA 平台刷新单表 ──────────────────────────────────────

    @PostMapping("/refresh-single/{tableName}")
    public Result<TableMeta> refreshSingle(@PathVariable String tableName) {
        if (remoteDdlCollector == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            TableMeta updated = tableMetaService.refreshSingleFromRemote(tableName);
            return Result.ok(updated);
        } catch (Exception e) {
            return Result.fail("刷新失败: " + e.getMessage());
        }
    }

    // ─── 初始化：从 table-meta 目录导入到 DB ──────────────────────

    @PostMapping("/init-from-files")
    public Result<Map<String, Object>> initFromFiles() {
        try {
            Map<String, Object> result = tableMetaService.importFromFiles();
            return Result.ok(result);
        } catch (Exception e) {
            return Result.fail("初始化失败: " + e.getMessage());
        }
    }

    // ─── 批量从远程采集并入库 ──────────────────────────────────────

    @PostMapping("/refresh-ddl")
    public Result<Map<String, Object>> refreshDdl(@RequestParam(defaultValue = "-1") int limit) {
        if (remoteDdlCollector == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            RemoteDdlCollector.RefreshResult result = remoteDdlCollector.refreshDdl(limit);
            tableMetaService.syncFilesToDb();
            return Result.ok(Map.of(
                    "total", result.total(),
                    "success", result.success(),
                    "failed", result.failed(),
                    "updated", result.updated(),
                    "updatedTables", result.updatedTables(),
                    "failedTables", result.failedTables()
            ));
        } catch (Exception e) {
            return Result.fail("DDL refresh failed: " + e.getMessage());
        }
    }

    @PostMapping("/refresh-index-stats")
    public Result<Map<String, Object>> refreshIndexStats(@RequestParam(defaultValue = "-1") int limit) {
        if (remoteDdlCollector == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            RemoteDdlCollector.RefreshResult result = remoteDdlCollector.refreshIndexStats(limit);
            tableMetaService.syncFilesToDb();
            return Result.ok(Map.of(
                    "total", result.total(),
                    "success", result.success(),
                    "failed", result.failed(),
                    "updated", result.updated(),
                    "updatedTables", result.updatedTables(),
                    "failedTables", result.failedTables()
            ));
        } catch (Exception e) {
            return Result.fail("Index stats refresh failed: " + e.getMessage());
        }
    }

    @PostMapping("/remote-collect")
    public Result<Map<String, Object>> remoteCollect(
            @RequestParam(defaultValue = "true") boolean reAnalyzeChanged) {
        if (remoteDdlCollector == null) {
            return Result.fail(PLATFORM_NOT_CONFIGURED);
        }
        try {
            RemoteDdlCollector.CollectionResult result = remoteDdlCollector.collectAll();
            tableMetaService.syncFilesToDb();
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

    @PostMapping("/reload-cache")
    public Result<Map<String, Object>> reloadCache() {
        cacheService.reload();
        return Result.ok(Map.of("cacheSize", cacheService.size()));
    }
}
