package com.zhuangjie.sentinel.scanner;

import cn.hutool.crypto.SecureUtil;

/**
 * Normalizes SQL text for hashing and comparison.
 */
public final class SqlNormalizer {

    private SqlNormalizer() {
    }

    /**
     * Replaces MyBatis parameter placeholders with '?' and normalizes whitespace.
     */
    public static String normalize(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return sql
                .replaceAll("#\\{[^}]*}", "?")
                .replaceAll("\\$\\{[^}]*}", "?")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Computes a SHA-256 hash of the normalized (lowercased) SQL for deduplication.
     */
    public static String hash(String normalizedSql) {
        if (normalizedSql == null || normalizedSql.isBlank()) {
            return "";
        }
        return SecureUtil.sha256(normalizedSql.toLowerCase().trim());
    }
}
