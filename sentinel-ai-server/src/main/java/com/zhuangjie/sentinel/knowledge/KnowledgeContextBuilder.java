package com.zhuangjie.sentinel.knowledge;

import com.zhuangjie.sentinel.db.entity.TableMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL 表结构上下文构建器。
 * <p>
 * 优先从 {@link TableMetaCacheService} 内存缓存获取表结构；
 * 缓存未命中时降级到磁盘 DDL 文件，保证向后兼容。
 */
@Slf4j
@Component
public class KnowledgeContextBuilder {

    private static final Pattern ESTIMATED_ROWS_PATTERN =
            Pattern.compile("--\\s*Estimated Rows:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SHARD_SUFFIX_PATTERN =
            Pattern.compile("^(.+?)(_\\d{4,}|_\\d{1,2})$");

    private final TableNameExtractor tableNameExtractor;
    private final TableMetaCacheService cacheService;
    private final Path tableMetaDir;

    public KnowledgeContextBuilder(
            TableNameExtractor tableNameExtractor,
            TableMetaCacheService cacheService,
            @Value("${sentinel.knowledge.table-meta-dir:table-meta}") String tableMetaDirStr) {
        this.tableNameExtractor = tableNameExtractor;
        this.cacheService = cacheService;
        this.tableMetaDir = Path.of(tableMetaDirStr);
        log.info("KnowledgeContextBuilder initialized, table-meta-dir: {}, cache: {} tables",
                tableMetaDir.toAbsolutePath(), cacheService.size());
    }

    public boolean isAvailable() {
        return cacheService.size() > 0 || Files.isDirectory(tableMetaDir);
    }

    /**
     * 根据 SQL 中引用的表名构建 AI prompt 上下文。
     * 流程：提取表名 → 缓存查找（降级文件） → 格式化为上下文字符串。
     */
    public String buildContext(String sql) {
        Set<String> tableNames = tableNameExtractor.extract(sql);
        if (tableNames.isEmpty()) {
            return "";
        }

        Set<String> resolvedBaseNames = new HashSet<>();
        List<TableFileContent> matched = new ArrayList<>();

        for (String tableName : tableNames) {
            TableFileContent content = resolveFromCache(tableName, resolvedBaseNames);
            if (content == null) {
                content = resolveFromFile(tableName, resolvedBaseNames);
            }
            if (content != null) {
                matched.add(content);
            }
        }

        if (matched.isEmpty()) {
            log.debug("No DDL found for tables {}", tableNames);
            return "";
        }

        return formatContext(matched);
    }

    /** 从缓存中获取表结构 */
    private TableFileContent resolveFromCache(String tableName, Set<String> resolvedBaseNames) {
        TableMeta meta = cacheService.get(tableName);
        if (meta == null || meta.getDdlText() == null) return null;

        String baseName = meta.getTableName().toLowerCase();
        if (!resolvedBaseNames.add(baseName)) return null;

        boolean isSharded = !baseName.equalsIgnoreCase(tableName);
        String displayName = isSharded
                ? tableName + " → base: " + baseName
                : tableName;

        StringBuilder contentBuilder = new StringBuilder();
        if (meta.getEstimatedRows() != null && meta.getEstimatedRows() > 0) {
            contentBuilder.append("-- Estimated Rows: ").append(meta.getEstimatedRows()).append("\n\n");
        }
        contentBuilder.append(meta.getDdlText());
        if (meta.getIndexStats() != null && !meta.getIndexStats().isBlank()) {
            contentBuilder.append("\n\n").append(meta.getIndexStats());
        }

        long rows = meta.getEstimatedRows() != null ? meta.getEstimatedRows() : 0;
        return new TableFileContent(displayName, contentBuilder.toString().trim(), rows, isSharded);
    }

    /** 降级：从文件系统获取表结构 */
    private TableFileContent resolveFromFile(String tableName, Set<String> resolvedBaseNames) {
        if (!Files.isDirectory(tableMetaDir)) return null;

        Path ddlFile = resolveDdlFile(tableMetaDir, tableName);
        if (ddlFile == null) return null;

        String baseName = ddlFile.getFileName().toString().replace(".sql", "");
        if (!resolvedBaseNames.add(baseName)) return null;

        try {
            String content = Files.readString(ddlFile, StandardCharsets.UTF_8);
            long estimatedRows = extractEstimatedRows(content);
            boolean isSharded = !baseName.equalsIgnoreCase(tableName);
            String displayName = isSharded
                    ? tableName + " → base: " + baseName
                    : tableName;
            return new TableFileContent(displayName, content.trim(), estimatedRows, isSharded);
        } catch (IOException e) {
            log.warn("Failed to read DDL file {}: {}", ddlFile, e.getMessage());
            return null;
        }
    }

    private Path resolveDdlFile(Path projectDir, String tableName) {
        Path exact = projectDir.resolve(tableName + ".sql");
        if (Files.exists(exact)) return exact;

        Path exactLower = projectDir.resolve(tableName.toLowerCase() + ".sql");
        if (Files.exists(exactLower)) return exactLower;

        Matcher m = SHARD_SUFFIX_PATTERN.matcher(tableName.toLowerCase());
        if (m.matches()) {
            String baseTable = m.group(1);
            Path basePath = projectDir.resolve(baseTable + ".sql");
            if (Files.exists(basePath)) {
                log.debug("Sharded table {} mapped to base table {}", tableName, baseTable);
                return basePath;
            }
        }

        return null;
    }

    private long extractEstimatedRows(String content) {
        Matcher m = ESTIMATED_ROWS_PATTERN.matcher(content);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private String formatContext(List<TableFileContent> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 相关表结构（自动检索）\n\n");

        for (TableFileContent table : tables) {
            sb.append("### ").append(table.tableName);
            if (table.isSharded) {
                sb.append("（分表）");
            }
            if (table.estimatedRows > 0) {
                sb.append("（约 ").append(String.format("%,d", table.estimatedRows)).append(" 行）");
            }
            sb.append("\n```sql\n").append(table.content).append("\n```\n\n");
        }

        return sb.toString().trim();
    }

    private record TableFileContent(String tableName, String content, long estimatedRows, boolean isSharded) {
    }
}
