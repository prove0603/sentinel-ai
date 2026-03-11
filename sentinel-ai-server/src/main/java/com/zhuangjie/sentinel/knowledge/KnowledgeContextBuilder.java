package com.zhuangjie.sentinel.knowledge;

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
 * Builds table-structure context for AI prompts by reading DDL files from disk.
 * <p>
 * Directory layout: {@code {table-meta-dir}/{project-name}/{table_name}.sql}
 * <p>
 * Each .sql file contains SHOW CREATE TABLE output with an optional
 * {@code -- Estimated Rows: N} header comment.
 */
@Slf4j
@Component
public class KnowledgeContextBuilder {

    private static final Pattern ESTIMATED_ROWS_PATTERN =
            Pattern.compile("--\\s*Estimated Rows:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Matches sharded table suffixes: _202301, _2023, _20230101, _1, _2 etc. */
    private static final Pattern SHARD_SUFFIX_PATTERN =
            Pattern.compile("^(.+?)(_\\d{4,}|_\\d{1,2})$");

    private final TableNameExtractor tableNameExtractor;
    private final Path tableMetaDir;

    public KnowledgeContextBuilder(
            TableNameExtractor tableNameExtractor,
            @Value("${sentinel.knowledge.table-meta-dir:table-meta}") String tableMetaDirStr) {
        this.tableNameExtractor = tableNameExtractor;
        this.tableMetaDir = Path.of(tableMetaDirStr);
        log.info("Knowledge context builder initialized, table-meta-dir: {}", tableMetaDir.toAbsolutePath());
    }

    public boolean isAvailable() {
        return Files.isDirectory(tableMetaDir);
    }

    /**
     * Builds a context string containing relevant table structures for the given SQL.
     *
     * @param sql         the normalized SQL text
     * @param projectName the project name (used as subdirectory under table-meta-dir)
     * @return formatted context string, or empty string if no context found
     */
    public String buildContext(String sql, String projectName) {
        Set<String> tableNames = tableNameExtractor.extract(sql);
        if (tableNames.isEmpty()) {
            return "";
        }

        Path projectDir = tableMetaDir.resolve(projectName);
        if (!Files.isDirectory(projectDir)) {
            log.debug("No table-meta directory for project: {}", projectName);
            return "";
        }

        Set<String> resolvedBaseNames = new HashSet<>();
        List<TableFileContent> matched = new ArrayList<>();
        for (String tableName : tableNames) {
            Path ddlFile = resolveDdlFile(projectDir, tableName);
            if (ddlFile == null) continue;

            String baseName = ddlFile.getFileName().toString().replace(".sql", "");
            if (!resolvedBaseNames.add(baseName)) continue;

            try {
                String content = Files.readString(ddlFile, StandardCharsets.UTF_8);
                long estimatedRows = extractEstimatedRows(content);
                boolean isSharded = !baseName.equalsIgnoreCase(tableName);
                String displayName = isSharded
                        ? tableName + " → base: " + baseName
                        : tableName;
                matched.add(new TableFileContent(displayName, content.trim(), estimatedRows, isSharded));
            } catch (IOException e) {
                log.warn("Failed to read DDL file {}: {}", ddlFile, e.getMessage());
            }
        }

        if (matched.isEmpty()) {
            log.debug("No DDL files found for tables {} in project {}", tableNames, projectName);
            return "";
        }

        return formatContext(matched);
    }

    /**
     * Overload that accepts projectId — resolves project name from the configured projects.
     * Falls back to using projectId as directory name.
     */
    public String buildContext(String sql, Long projectId) {
        return buildContext(sql, String.valueOf(projectId));
    }

    /**
     * Resolves project directory name: tries to find a matching subdirectory, otherwise uses project name directly.
     */
    public String buildContextByProjectName(String sql, String projectName) {
        return buildContext(sql, projectName);
    }

    /**
     * Resolves a DDL file for the given table name:
     * 1. Exact match: {tableName}.sql
     * 2. Shard fallback: strip numeric suffix and try base table (e.g. t_log_202603 → t_log.sql)
     */
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
