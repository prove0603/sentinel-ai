package com.zhuangjie.sentinel.pojo.dto;

import com.zhuangjie.sentinel.pojo.enums.SqlSourceType;

/**
 * Represents a SQL statement extracted from source code during scanning.
 *
 * @param sql              the raw SQL text
 * @param sqlNormalized    the normalized SQL (parameters replaced with ?)
 * @param sqlType          SELECT / INSERT / UPDATE / DELETE
 * @param sourceType       where the SQL comes from
 * @param sourceFile       the file path
 * @param sourceLocation   namespace.id or ClassName.methodName
 * @param hasDynamicSql    whether this SQL was expanded from dynamic SQL (if/choose/where etc.)
 */
public record ScannedSql(
        String sql,
        String sqlNormalized,
        String sqlType,
        SqlSourceType sourceType,
        String sourceFile,
        String sourceLocation,
        boolean hasDynamicSql
) {
    public ScannedSql(String sql, String sqlNormalized, String sqlType,
                      SqlSourceType sourceType, String sourceFile, String sourceLocation) {
        this(sql, sqlNormalized, sqlType, sourceType, sourceFile, sourceLocation, false);
    }
}
