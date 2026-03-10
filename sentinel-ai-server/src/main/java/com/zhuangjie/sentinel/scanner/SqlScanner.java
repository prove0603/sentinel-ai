package com.zhuangjie.sentinel.scanner;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;

import java.nio.file.Path;
import java.util.List;

/**
 * Scans a project directory and extracts SQL statements from various sources.
 */
public interface SqlScanner {

    List<ScannedSql> scan(Path projectRoot);
}
