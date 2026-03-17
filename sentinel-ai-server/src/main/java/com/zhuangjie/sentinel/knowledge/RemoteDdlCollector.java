package com.zhuangjie.sentinel.knowledge;

import com.zhuangjie.sentinel.knowledge.DataPlatformClient.QueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collects DDL and index statistics from production database via remote data platform APIs.
 * <p>
 * This component is optional — when the data platform is not configured,
 * users can manually maintain DDL files in the table-meta directory.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sentinel.data-platform.enabled", havingValue = "true")
public class RemoteDdlCollector {

    private static final String INDEX_STATS_MARKER = "-- === Index Statistics (auto-collected) ===";

    private final DataPlatformClient client;
    private final DataPlatformConfig config;

    @Value("${sentinel.knowledge.table-meta-dir:table-meta}")
    private String tableMetaDir;

    public RemoteDdlCollector(DataPlatformClient client, DataPlatformConfig config) {
        this.client = client;
        this.config = config;
    }

    /**
     * Refreshes DDL for tables that already have files in table-meta/.
     * Only queries tables that already exist locally — does NOT discover new tables.
     *
     * @param limit max number of tables to process, -1 for no limit
     * @return refresh result
     */
    public RefreshResult refreshDdl(int limit) throws IOException, InterruptedException {
        List<String> localTables = listLocalTables();
        if (localTables.isEmpty()) {
            log.info("[refreshDdl] No local table-meta files found, nothing to refresh");
            return new RefreshResult(0, 0, 0, 0, List.of());
        }

        List<String> tablesToProcess = applyLimit(localTables, limit);
        log.info("[refreshDdl] Starting DDL refresh: {} tables to process (limit={})",
                tablesToProcess.size(), limit);

        Path metaDir = Path.of(tableMetaDir);
        int batchSize = config.getCollectBatchSize();
        int success = 0, failed = 0, updated = 0;
        List<String> updatedTables = new ArrayList<>();

        List<List<String>> batches = partition(tablesToProcess, batchSize);
        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            log.info("[refreshDdl] Batch {}/{} — processing {} tables: {}",
                    i + 1, batches.size(), batch.size(), batch);

            for (String tableName : batch) {
                try {
                    log.debug("[refreshDdl] Querying DDL for table: {}", tableName);
                    String ddl = fetchCreateTable(tableName);
                    if (ddl == null || ddl.isBlank()) {
                        log.warn("[refreshDdl] No DDL returned for table: {}", tableName);
                        failed++;
                        continue;
                    }
                    log.debug("[refreshDdl] Got DDL for {} ({} chars)", tableName, ddl.length());

                    long rows = fetchSingleTableRowCount(tableName);
                    log.debug("[refreshDdl] Estimated rows for {}: {}", tableName, rows);

                    String newDdlSection = "-- Estimated Rows: " + rows + "\n\n" + ddl + "\n";
                    Path file = metaDir.resolve(tableName + ".sql");

                    String existingContent = Files.readString(file, StandardCharsets.UTF_8);
                    String existingIndexStats = extractIndexStatsSection(existingContent);
                    String newContent = existingIndexStats != null
                            ? newDdlSection + "\n" + existingIndexStats
                            : newDdlSection;

                    String existingDdlSection = extractDdlSection(existingContent);
                    if (!newDdlSection.trim().equals(existingDdlSection.trim())) {
                        updated++;
                        updatedTables.add(tableName);
                        log.info("[refreshDdl] DDL changed for table: {}", tableName);
                    } else {
                        log.debug("[refreshDdl] DDL unchanged for table: {}", tableName);
                    }

                    Files.writeString(file, newContent, StandardCharsets.UTF_8);
                    success++;
                    Thread.sleep(200);
                } catch (Exception e) {
                    log.warn("[refreshDdl] Failed for table {}: {}", tableName, e.getMessage());
                    failed++;
                }
            }

            log.info("[refreshDdl] Batch {}/{} done — success={}, failed={}, updated={}",
                    i + 1, batches.size(), success, failed, updated);

            if (i < batches.size() - 1) {
                Thread.sleep(1000);
            }
        }

        log.info("[refreshDdl] Completed: total={}, success={}, failed={}, updated={}, updatedTables={}",
                tablesToProcess.size(), success, failed, updated, updatedTables);
        return new RefreshResult(tablesToProcess.size(), success, failed, updated, updatedTables);
    }

    /**
     * Refreshes index statistics for tables that already have files in table-meta/.
     * Queries INFORMATION_SCHEMA.STATISTICS for each table and appends/updates
     * an index statistics section at the bottom of the .sql file.
     *
     * @param limit max number of tables to process, -1 for no limit
     * @return refresh result
     */
    public RefreshResult refreshIndexStats(int limit) throws IOException, InterruptedException {
        List<String> localTables = listLocalTables();
        if (localTables.isEmpty()) {
            log.info("[refreshIndexStats] No local table-meta files found, nothing to refresh");
            return new RefreshResult(0, 0, 0, 0, List.of());
        }

        List<String> tablesToProcess = applyLimit(localTables, limit);
        log.info("[refreshIndexStats] Starting index stats refresh: {} tables (limit={})",
                tablesToProcess.size(), limit);

        Path metaDir = Path.of(tableMetaDir);
        int batchSize = config.getDiffBatchSize();
        int success = 0, failed = 0, updated = 0;
        List<String> updatedTables = new ArrayList<>();

        List<List<String>> batches = partition(tablesToProcess, batchSize);
        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            log.info("[refreshIndexStats] Batch {}/{} — processing {} tables: {}",
                    i + 1, batches.size(), batch.size(), batch);

            for (String tableName : batch) {
                try {
                    log.debug("[refreshIndexStats] Querying index stats for table: {}", tableName);
                    String indexSection = fetchIndexStats(tableName);

                    Path file = metaDir.resolve(tableName + ".sql");
                    String existingContent = Files.readString(file, StandardCharsets.UTF_8);

                    String ddlSection = extractDdlSection(existingContent);
                    String oldIndexSection = extractIndexStatsSection(existingContent);

                    String newContent = indexSection != null
                            ? ddlSection + "\n" + indexSection
                            : ddlSection;

                    boolean changed = !Objects.equals(
                            oldIndexSection != null ? oldIndexSection.trim() : null,
                            indexSection != null ? indexSection.trim() : null);

                    if (changed) {
                        updated++;
                        updatedTables.add(tableName);
                        log.info("[refreshIndexStats] Index stats updated for table: {}", tableName);
                    } else {
                        log.debug("[refreshIndexStats] Index stats unchanged for table: {}", tableName);
                    }

                    Files.writeString(file, newContent, StandardCharsets.UTF_8);
                    success++;
                    Thread.sleep(200);
                } catch (Exception e) {
                    log.warn("[refreshIndexStats] Failed for table {}: {}", tableName, e.getMessage());
                    failed++;
                }
            }

            log.info("[refreshIndexStats] Batch {}/{} done — success={}, failed={}, updated={}",
                    i + 1, batches.size(), success, failed, updated);

            if (i < batches.size() - 1) {
                Thread.sleep(1000);
            }
        }

        log.info("[refreshIndexStats] Completed: total={}, success={}, failed={}, updated={}, updatedTables={}",
                tablesToProcess.size(), success, failed, updated, updatedTables);
        return new RefreshResult(tablesToProcess.size(), success, failed, updated, updatedTables);
    }

    /**
     * Collects DDL for all tables (full collection from scratch).
     */
    public CollectionResult collectAll() throws IOException, InterruptedException {
        Path outputDir = Path.of(tableMetaDir);
        Files.createDirectories(outputDir);

        Map<String, Long> tableRows = fetchTableRowCounts();
        log.info("Found {} tables to collect DDL from production", tableRows.size());

        int batchSize = config.getCollectBatchSize();
        int success = 0, failed = 0, changed = 0;
        List<String> changedTables = new ArrayList<>();

        List<Map.Entry<String, Long>> entries = new ArrayList<>(tableRows.entrySet());
        List<List<Map.Entry<String, Long>>> batches = partition(entries, batchSize);

        for (int i = 0; i < batches.size(); i++) {
            List<Map.Entry<String, Long>> batch = batches.get(i);
            log.info("Processing batch {}/{} ({} tables)", i + 1, batches.size(), batch.size());

            for (Map.Entry<String, Long> entry : batch) {
                String tableName = entry.getKey();
                long rows = entry.getValue();
                try {
                    String ddl = fetchCreateTable(tableName);
                    if (ddl == null || ddl.isBlank()) {
                        failed++;
                        continue;
                    }
                    String newContent = "-- Estimated Rows: " + rows + "\n\n" + ddl + "\n";
                    Path file = outputDir.resolve(tableName + ".sql");
                    if (Files.exists(file)) {
                        String oldContent = Files.readString(file, StandardCharsets.UTF_8);
                        if (!oldContent.equals(newContent)) {
                            changed++;
                            changedTables.add(tableName);
                        }
                    } else {
                        changed++;
                        changedTables.add(tableName);
                    }
                    Files.writeString(file, newContent, StandardCharsets.UTF_8);
                    success++;
                    Thread.sleep(200);
                } catch (Exception e) {
                    log.warn("Failed to collect DDL for {}: {}", tableName, e.getMessage());
                    failed++;
                }
            }

            if (i < batches.size() - 1) Thread.sleep(1000);
        }

        log.info("DDL collection completed: total={}, success={}, failed={}, changed={}",
                tableRows.size(), success, failed, changed);
        return new CollectionResult(tableRows.size(), success, failed, changed, changedTables);
    }

    // ─── Index Stats Fetching ──────────────────────────────────────

    private String fetchIndexStats(String tableName) throws IOException, InterruptedException {
        String sql = "SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE, CARDINALITY, INDEX_TYPE " +
                "FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + escapeQuote(tableName) + "' " +
                "ORDER BY INDEX_NAME, SEQ_IN_INDEX;";

        QueryResult result = client.executeQuery(sql);
        if (result.rows().isEmpty()) {
            log.debug("[fetchIndexStats] No index data for table: {}", tableName);
            return null;
        }

        int nameIdx = Math.max(result.headers().indexOf("INDEX_NAME"), 0);
        int colIdx = Math.max(result.headers().indexOf("COLUMN_NAME"), 2);
        int uniqueIdx = Math.max(result.headers().indexOf("NON_UNIQUE"), 3);
        int cardIdx = Math.max(result.headers().indexOf("CARDINALITY"), 4);
        int typeIdx = Math.max(result.headers().indexOf("INDEX_TYPE"), 5);

        // Group by index name, preserving order
        Map<String, List<List<String>>> indexGroups = new LinkedHashMap<>();
        for (List<String> row : result.rows()) {
            String idxName = row.get(nameIdx);
            indexGroups.computeIfAbsent(idxName, k -> new ArrayList<>()).add(row);
        }

        long totalRows = fetchSingleTableRowCount(tableName);
        log.debug("[fetchIndexStats] Table {} has {} rows, {} indexes",
                tableName, totalRows, indexGroups.size());

        StringBuilder sb = new StringBuilder();
        sb.append(INDEX_STATS_MARKER).append("\n");
        sb.append("-- Table Rows: ").append(totalRows).append("\n");

        for (Map.Entry<String, List<List<String>>> entry : indexGroups.entrySet()) {
            String idxName = entry.getKey();
            List<List<String>> rows = entry.getValue();

            String columns = rows.stream()
                    .map(r -> r.get(colIdx))
                    .collect(Collectors.joining(", "));

            String nonUnique = rows.get(0).get(uniqueIdx);
            boolean unique = "0".equals(nonUnique);
            String cardinality = rows.get(rows.size() - 1).get(cardIdx);
            String indexType = rows.get(0).get(typeIdx);

            double selectivity = 0;
            if (totalRows > 0 && cardinality != null && !cardinality.equalsIgnoreCase("null")) {
                try {
                    selectivity = Double.parseDouble(cardinality) / totalRows;
                } catch (NumberFormatException ignored) {
                }
            }

            sb.append(String.format("-- Index: %s | Columns: %s | Cardinality: %s | Unique: %s | Type: %s | Selectivity: %.4f%n",
                    idxName, columns,
                    cardinality != null ? cardinality : "N/A",
                    unique ? "YES" : "NO",
                    indexType != null ? indexType : "N/A",
                    selectivity));

            log.debug("[fetchIndexStats]   {} -> columns=[{}], cardinality={}, unique={}, selectivity={:.4f}",
                    idxName, columns, cardinality, unique, selectivity);
        }

        return sb.toString();
    }

    // ─── File Section Helpers ──────────────────────────────────────

    private String extractDdlSection(String fileContent) {
        int markerPos = fileContent.indexOf(INDEX_STATS_MARKER);
        if (markerPos < 0) return fileContent;
        return fileContent.substring(0, markerPos).stripTrailing() + "\n";
    }

    private String extractIndexStatsSection(String fileContent) {
        int markerPos = fileContent.indexOf(INDEX_STATS_MARKER);
        if (markerPos < 0) return null;
        return fileContent.substring(markerPos);
    }

    // ─── Local File Helpers ────────────────────────────────────────

    private List<String> listLocalTables() throws IOException {
        Path metaDir = Path.of(tableMetaDir);
        if (!Files.isDirectory(metaDir)) return List.of();
        try (var stream = Files.list(metaDir)) {
            return stream.filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> p.getFileName().toString().replace(".sql", ""))
                    .sorted()
                    .toList();
        }
    }

    private List<String> applyLimit(List<String> tables, int limit) {
        if (limit > 0 && limit < tables.size()) {
            return tables.subList(0, limit);
        }
        return tables;
    }

    // ─── Remote Query Helpers ──────────────────────────────────────

    private Map<String, Long> fetchTableRowCounts() throws IOException, InterruptedException {
        String sql = "SELECT TABLE_NAME, TABLE_ROWS FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME;";
        QueryResult result = client.executeQuery(sql);

        int tableNameIdx = Math.max(result.headers().indexOf("TABLE_NAME"), 0);
        int tableRowsIdx = Math.max(result.headers().indexOf("TABLE_ROWS"), 1);

        Map<String, Long> map = new LinkedHashMap<>();
        for (List<String> row : result.rows()) {
            String name = row.get(tableNameIdx);
            long rows = 0;
            try {
                String rowStr = row.get(tableRowsIdx);
                if (rowStr != null && !rowStr.equalsIgnoreCase("null")) {
                    rows = Long.parseLong(rowStr);
                }
            } catch (NumberFormatException ignored) {
            }
            map.put(name, rows);
        }
        return map;
    }

    private long fetchSingleTableRowCount(String tableName) throws IOException, InterruptedException {
        String sql = "SELECT TABLE_ROWS FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + escapeQuote(tableName) + "';";
        QueryResult result = client.executeQuery(sql);
        if (result.rows().isEmpty()) return 0;
        try {
            return Long.parseLong(result.rows().get(0).get(0));
        } catch (Exception e) {
            return 0;
        }
    }

    private String fetchCreateTable(String tableName) throws IOException, InterruptedException {
        String sql = "SHOW CREATE TABLE `" + tableName.replace("`", "``") + "`;";
        try {
            QueryResult result = client.executeQuery(sql);
            if (result.rows().isEmpty()) return null;
            List<String> row = result.rows().get(0);
            return row.size() >= 2 ? row.get(1) : row.get(0);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("auth")) {
                client.refreshToken();
                QueryResult result = client.executeQuery(sql);
                if (result.rows().isEmpty()) return null;
                List<String> row = result.rows().get(0);
                return row.size() >= 2 ? row.get(1) : row.get(0);
            }
            throw e;
        }
    }

    private String escapeQuote(String str) {
        return str.replace("'", "\\'");
    }

    // ─── Utility ───────────────────────────────────────────────────

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    /**
     * Detects DDL changes by comparing local table-meta files with current production DDL.
     */
    public DiffResult detectChanges() throws IOException, InterruptedException {
        Path metaDir = Path.of(tableMetaDir);
        if (!Files.isDirectory(metaDir)) {
            return new DiffResult(0, List.of(), List.of(), List.of());
        }

        Map<String, Long> remoteTableRows = fetchTableRowCounts();
        Set<String> remoteTables = remoteTableRows.keySet();
        Set<String> localTables = new LinkedHashSet<>(listLocalTables());

        List<String> deletedTables = localTables.stream().filter(t -> !remoteTables.contains(t)).sorted().toList();
        List<String> newTables = remoteTables.stream().filter(t -> !localTables.contains(t)).sorted().toList();
        List<String> tablesToCheck = remoteTables.stream().filter(localTables::contains).sorted().toList();

        int batchSize = config.getDiffBatchSize();
        List<String> changedTables = new ArrayList<>();
        List<List<String>> batches = partition(tablesToCheck, batchSize);

        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            log.info("Diff batch {}/{} ({} tables)", i + 1, batches.size(), batch.size());

            for (String tableName : batch) {
                try {
                    String remoteDdl = fetchCreateTable(tableName);
                    if (remoteDdl == null) continue;
                    long rows = remoteTableRows.getOrDefault(tableName, 0L);
                    String remoteContent = "-- Estimated Rows: " + rows + "\n\n" + remoteDdl + "\n";
                    Path file = metaDir.resolve(tableName + ".sql");
                    String localContent = Files.readString(file, StandardCharsets.UTF_8);
                    String localDdl = extractDdlSection(localContent);
                    if (!localDdl.trim().equals(remoteContent.trim())) {
                        changedTables.add(tableName);
                    }
                    Thread.sleep(200);
                } catch (Exception e) {
                    log.warn("Failed to diff DDL for {}: {}", tableName, e.getMessage());
                }
            }
            if (i < batches.size() - 1) Thread.sleep(1000);
        }

        int totalChecked = tablesToCheck.size() + newTables.size() + deletedTables.size();
        log.info("DDL diff completed: checked={}, changed={}, new={}, deleted={}",
                totalChecked, changedTables.size(), newTables.size(), deletedTables.size());
        return new DiffResult(totalChecked, changedTables, newTables, deletedTables);
    }

    public String collectTable(String tableName) throws IOException, InterruptedException {
        String ddl = fetchCreateTable(tableName);
        if (ddl == null) return null;
        long rows = fetchSingleTableRowCount(tableName);
        return "-- Estimated Rows: " + rows + "\n\n" + ddl + "\n";
    }

    // ─── Result Records ────────────────────────────────────────────

    public record RefreshResult(int total, int success, int failed, int updated, List<String> updatedTables) {
    }

    public record CollectionResult(int totalTables, int successCount, int failedCount,
                                   int changedCount, List<String> changedTables) {
    }

    public record DiffResult(int totalChecked, List<String> changedTables,
                             List<String> newTables, List<String> deletedTables) {
    }
}
