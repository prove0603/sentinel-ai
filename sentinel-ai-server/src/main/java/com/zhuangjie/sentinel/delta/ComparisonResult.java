package com.zhuangjie.sentinel.delta;

import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;

import java.util.List;

/**
 * Result of comparing current scan SQL hashes with existing database records.
 *
 * @param newSqls         SQL that didn't exist before (new hash)
 * @param unchangedHashes SQL hashes that already exist in the database
 * @param removedRecords  database records whose hash is no longer present in the scanned files
 */
public record ComparisonResult(
        List<ScannedSql> newSqls,
        List<String> unchangedHashes,
        List<SqlRecord> removedRecords
) {
}
