package com.zhuangjie.sentinel.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * Collects table metadata (DDL via SHOW CREATE TABLE, row estimates) from a target MySQL database.
 * Returns lightweight records that can be saved as DDL files.
 */
@Slf4j
@Component
public class TableMetaCollector {

    public record CollectedTable(String tableName, String ddl, long estimatedRows) {
    }

    /**
     * Connects to the target database and collects DDL for all tables.
     */
    public List<CollectedTable> collect(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            log.warn("Cannot collect table metadata: empty JDBC URL");
            return Collections.emptyList();
        }

        List<CollectedTable> results = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            String catalog = conn.getCatalog();
            log.info("Collecting table metadata from database: {}", catalog);

            Map<String, Long> rowCounts = collectRowCounts(conn, catalog);

            for (Map.Entry<String, Long> entry : rowCounts.entrySet()) {
                String tableName = entry.getKey();
                long rows = entry.getValue();
                String ddl = showCreateTable(conn, tableName);
                if (ddl != null) {
                    results.add(new CollectedTable(tableName, ddl, rows));
                }
            }

            log.info("Collected DDL for {} tables from {}", results.size(), catalog);
        } catch (SQLException e) {
            log.error("Failed to collect table metadata from {}: {}", jdbcUrl, e.getMessage());
        }

        return results;
    }

    private Map<String, Long> collectRowCounts(Connection conn, String catalog) throws SQLException {
        Map<String, Long> map = new LinkedHashMap<>();
        String sql = """
                SELECT TABLE_NAME, TABLE_ROWS
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_NAME
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, catalog);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("TABLE_NAME"), rs.getLong("TABLE_ROWS"));
                }
            }
        }
        return map;
    }

    private String showCreateTable(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SHOW CREATE TABLE `" + tableName.replace("`", "``") + "`")) {
            if (rs.next()) {
                return rs.getString(2);
            }
        } catch (SQLException e) {
            log.debug("SHOW CREATE TABLE failed for {}: {}", tableName, e.getMessage());
        }
        return null;
    }
}
