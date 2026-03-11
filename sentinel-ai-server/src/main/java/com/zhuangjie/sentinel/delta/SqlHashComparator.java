package com.zhuangjie.sentinel.delta;

import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.scanner.SqlNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Compares current scan SQL hashes with existing database records
 * to determine new, unchanged, and removed SQL.
 */
@Slf4j
@Component
public class SqlHashComparator {

    /**
     * Compares SQL extracted from changed files against existing records in the database.
     *
     * @param currentSqls     SQL extracted from the re-scanned files
     * @param existingRecords active SqlRecord entries from DB for the same source files
     * @return comparison result
     */
    public ComparisonResult compare(List<ScannedSql> currentSqls, List<SqlRecord> existingRecords) {
        Map<String, ScannedSql> currentByHash = new LinkedHashMap<>();
        for (ScannedSql sql : currentSqls) {
            String hash = SqlNormalizer.hash(sql.sqlNormalized());
            if (!hash.isBlank()) {
                currentByHash.putIfAbsent(hash, sql);
            }
        }

        Set<String> existingHashes = existingRecords.stream()
                .map(SqlRecord::getSqlHash)
                .collect(Collectors.toSet());

        List<ScannedSql> newSqls = new ArrayList<>();
        List<String> unchangedHashes = new ArrayList<>();

        for (var entry : currentByHash.entrySet()) {
            if (existingHashes.contains(entry.getKey())) {
                unchangedHashes.add(entry.getKey());
            } else {
                newSqls.add(entry.getValue());
            }
        }

        Set<String> currentHashes = currentByHash.keySet();
        List<SqlRecord> removedRecords = existingRecords.stream()
                .filter(r -> !currentHashes.contains(r.getSqlHash()))
                .toList();

        log.info("SQL hash comparison: new={}, unchanged={}, removed={}",
                newSqls.size(), unchangedHashes.size(), removedRecords.size());

        return new ComparisonResult(newSqls, unchangedHashes, removedRecords);
    }
}
