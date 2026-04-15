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
 * DDL 表结构上下文构建器。
 * <p>
 * 从磁盘读取 DDL 文件，为 AI 分析 prompt 构建表结构上下文。
 * 目录结构：{@code {table-meta-dir}/{table_name}.sql}（扁平目录，每张表一个文件）
 * <p>
 * DDL 文件格式：{@code -- Estimated Rows: N} 头部注释 + SHOW CREATE TABLE 输出。
 * 支持分表智能匹配：如 t_log_202603 会自动匹配 t_log.sql。
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
     * 根据 SQL 中引用的表名，从 DDL 文件读取表结构，构建 AI prompt 上下文。
     * 流程：提取表名 → 查找 DDL 文件（含分表匹配） → 读取内容 → 格式化为上下文字符串。
     */
    public String buildContext(String sql) {
        Set<String> tableNames = tableNameExtractor.extract(sql);
        if (tableNames.isEmpty()) {
            return "";
        }

        if (!Files.isDirectory(tableMetaDir)) {
            log.debug("table-meta directory does not exist: {}", tableMetaDir);
            return "";
        }

        Set<String> resolvedBaseNames = new HashSet<>();
        List<TableFileContent> matched = new ArrayList<>();
        for (String tableName : tableNames) {
            Path ddlFile = resolveDdlFile(tableMetaDir, tableName);
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
            log.debug("No DDL files found for tables {}", tableNames);
            return "";
        }

        return formatContext(matched);
    }

    /**
     * 解析 DDL 文件路径：先精确匹配，未找到则尝试去除分表后缀匹配基础表。
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
