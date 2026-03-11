package com.zhuangjie.sentinel.scanner;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;

import java.nio.file.Path;
import java.util.List;

/**
 * Scans a project directory and extracts SQL statements from various sources.
 */
public interface SqlScanner {

    List<ScannedSql> scan(Path projectRoot);

    /**
     * Scans only specific files (used for incremental scanning).
     * Default implementation falls back to a full scan.
     */
    default List<ScannedSql> scanFiles(Path projectRoot, List<Path> files) {
        return scan(projectRoot);
    }
}
