package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.db.entity.TableMeta;
import com.zhuangjie.sentinel.db.service.TableMetaDbService;
import com.zhuangjie.sentinel.knowledge.RemoteDdlCollector;
import com.zhuangjie.sentinel.knowledge.TableMetaCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表结构管理核心业务：导入文件到 DB、从远程平台刷新单表、同步文件到 DB。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableMetaService {

    private static final Pattern ESTIMATED_ROWS_PATTERN =
            Pattern.compile("--\\s*Estimated Rows:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final String INDEX_STATS_MARKER = "-- === Index Statistics (auto-collected) ===";

    private final TableMetaDbService tableMetaDbService;
    private final TableMetaCacheService cacheService;

    @Autowired(required = false)
    private RemoteDdlCollector remoteDdlCollector;

    @Value("${sentinel.knowledge.table-meta-dir:table-meta}")
    private String tableMetaDir;

    /**
     * 从 table-meta 目录批量导入到 DB。已存在的更新，不存在的新增。
     */
    public Map<String, Object> importFromFiles() throws IOException {
        Path dir = Path.of(tableMetaDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("table-meta 目录不存在: " + dir.toAbsolutePath());
        }

        List<Path> sqlFiles;
        try (var stream = Files.list(dir)) {
            sqlFiles = stream.filter(p -> p.toString().endsWith(".sql")).toList();
        }

        int created = 0, updated = 0, skipped = 0;
        List<String> errors = new ArrayList<>();

        for (Path file : sqlFiles) {
            String tableName = file.getFileName().toString().replace(".sql", "");
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                TableMeta meta = parseFileContent(tableName, content);

                TableMeta existing = tableMetaDbService.getOne(
                        new LambdaQueryWrapper<TableMeta>().eq(TableMeta::getTableName, tableName));
                if (existing != null) {
                    existing.setDdlText(meta.getDdlText());
                    existing.setEstimatedRows(meta.getEstimatedRows());
                    existing.setIndexStats(meta.getIndexStats());
                    tableMetaDbService.updateById(existing);
                    cacheService.put(existing);
                    updated++;
                } else {
                    tableMetaDbService.save(meta);
                    cacheService.put(meta);
                    created++;
                }
            } catch (Exception e) {
                log.warn("导入文件失败 {}: {}", tableName, e.getMessage());
                errors.add(tableName + ": " + e.getMessage());
                skipped++;
            }
        }

        log.info("[TableMeta] 文件导入完成: created={}, updated={}, skipped={}, total={}",
                created, updated, skipped, sqlFiles.size());

        return Map.of(
                "totalFiles", sqlFiles.size(),
                "created", created,
                "updated", updated,
                "skipped", skipped,
                "errors", errors
        );
    }

    /**
     * 从远程 DBA 平台刷新单张表的 DDL 和索引统计，同步到 DB 和缓存。
     */
    public TableMeta refreshSingleFromRemote(String tableName) throws Exception {
        if (remoteDdlCollector == null) {
            throw new IllegalStateException("Remote data platform not configured");
        }

        String ddlContent = remoteDdlCollector.collectTable(tableName);
        if (ddlContent == null || ddlContent.isBlank()) {
            throw new IllegalArgumentException("DBA 平台未找到表: " + tableName);
        }

        TableMeta meta = parseFileContent(tableName, ddlContent);

        TableMeta existing = tableMetaDbService.getOne(
                new LambdaQueryWrapper<TableMeta>().eq(TableMeta::getTableName, tableName));
        if (existing != null) {
            existing.setDdlText(meta.getDdlText());
            existing.setEstimatedRows(meta.getEstimatedRows());
            existing.setIndexStats(meta.getIndexStats());
            tableMetaDbService.updateById(existing);
            cacheService.put(existing);
            return existing;
        } else {
            tableMetaDbService.save(meta);
            cacheService.put(meta);
            return meta;
        }
    }

    /**
     * 将当前 table-meta 目录的文件同步到 DB（用于批量 refresh 后的数据同步）。
     */
    public void syncFilesToDb() {
        try {
            importFromFiles();
        } catch (IOException e) {
            log.warn("[TableMeta] syncFilesToDb 失败: {}", e.getMessage());
        }
    }

    /** 解析文件内容，提取 DDL、行数估算、索引统计 */
    private TableMeta parseFileContent(String tableName, String content) {
        TableMeta meta = new TableMeta();
        meta.setTableName(tableName);

        Matcher rowsMatcher = ESTIMATED_ROWS_PATTERN.matcher(content);
        if (rowsMatcher.find()) {
            try {
                meta.setEstimatedRows(Long.parseLong(rowsMatcher.group(1)));
            } catch (NumberFormatException ignored) {
                meta.setEstimatedRows(0L);
            }
        } else {
            meta.setEstimatedRows(0L);
        }

        int indexMarkerPos = content.indexOf(INDEX_STATS_MARKER);
        if (indexMarkerPos >= 0) {
            String ddlSection = content.substring(0, indexMarkerPos).trim();
            String indexSection = content.substring(indexMarkerPos).trim();

            ddlSection = stripEstimatedRowsHeader(ddlSection);
            meta.setDdlText(ddlSection);
            meta.setIndexStats(indexSection);
        } else {
            String ddlSection = stripEstimatedRowsHeader(content.trim());
            meta.setDdlText(ddlSection);
        }

        return meta;
    }

    /** 去掉 DDL 文本头部的 "-- Estimated Rows: N" 行 */
    private String stripEstimatedRowsHeader(String text) {
        return text.replaceFirst("^--\\s*Estimated Rows:\\s*\\d+\\s*\n*", "").trim();
    }
}
