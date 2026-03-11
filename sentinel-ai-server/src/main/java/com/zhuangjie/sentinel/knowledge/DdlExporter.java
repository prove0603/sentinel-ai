package com.zhuangjie.sentinel.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone utility to export DDL from a MySQL database into per-table .sql files.
 * <p>
 * Usage: {@code java DdlExporter <jdbcUrl> <outputDir>}
 * <p>
 * Each file includes {@code -- Estimated Rows: N} header and SHOW CREATE TABLE output.
 */
public class DdlExporter {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: DdlExporter <jdbcUrl> <outputDir>");
            System.out.println("Example: DdlExporter jdbc:mysql://host:3306/db?user=root&password=xxx table-meta/my-project");
            return;
        }

        String jdbcUrl = args[0];
        Path outputDir = Path.of(args[1]);
        Files.createDirectories(outputDir);

        System.out.println("Connecting to: " + jdbcUrl.replaceAll("password=[^&]*", "password=***"));

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            String catalog = conn.getCatalog();
            System.out.println("Database: " + catalog);

            Map<String, Long> tables = getTableRowCounts(conn, catalog);
            System.out.println("Found " + tables.size() + " tables");

            int count = 0;
            for (Map.Entry<String, Long> entry : tables.entrySet()) {
                String tableName = entry.getKey();
                long rows = entry.getValue();
                String ddl = showCreateTable(conn, tableName);
                if (ddl == null) {
                    System.out.println("  SKIP: " + tableName + " (no DDL)");
                    continue;
                }

                String content = "-- Estimated Rows: " + rows + "\n\n" + ddl + "\n";
                Path file = outputDir.resolve(tableName + ".sql");
                Files.writeString(file, content, StandardCharsets.UTF_8);
                count++;
                System.out.println("  OK: " + tableName + " (" + rows + " rows)");
            }

            System.out.println("\nDone! Exported " + count + " tables to " + outputDir.toAbsolutePath());
        }
    }

    private static Map<String, Long> getTableRowCounts(Connection conn, String catalog) throws SQLException {
        Map<String, Long> map = new LinkedHashMap<>();
        String sql = "SELECT TABLE_NAME, TABLE_ROWS FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
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

    private static String showCreateTable(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `" + tableName.replace("`", "``") + "`")) {
            if (rs.next()) return rs.getString(2);
        } catch (SQLException e) {
            System.err.println("  ERROR: " + tableName + " - " + e.getMessage());
        }
        return null;
    }
}
