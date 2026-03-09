package com.zhuangjie.sentinel.pojo.dto;

import com.zhuangjie.sentinel.pojo.enums.SqlSourceType;

/**
 * Represents a SQL statement extracted from source code during scanning.
 *
 * @param sql            the raw SQL text
 * @param sqlNormalized  the normalized SQL (parameters replaced with ?)
 * @param sqlType        SELECT / INSERT / UPDATE / DELETE
 * @param sourceType     where the SQL comes from
 * @param sourceFile     the file path
 * @param sourceLocation namespace.id or ClassName.methodName
 */
public record ScannedSql(
        String sql,
        String sqlNormalized,
        String sqlType,
        SqlSourceType sourceType,
        String sourceFile,
        String sourceLocation
) {
}
