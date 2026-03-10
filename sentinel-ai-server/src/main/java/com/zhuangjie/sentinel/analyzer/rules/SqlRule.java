package com.zhuangjie.sentinel.analyzer.rules;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import net.sf.jsqlparser.statement.Statement;

import java.util.List;

/**
 * Sealed interface for all SQL analysis rules.
 * Each rule is implemented as a stateless record.
 */
public sealed interface SqlRule permits
        FullTableScanRule,
        LikeLeadingWildcardRule,
        ImplicitConversionRule,
        LargeJoinRule,
        DeepPaginationRule,
        NoConditionDynamicSqlRule {

    String name();

    String description();

    /**
     * Analyzes a scanned SQL statement and returns any violations found.
     *
     * @param scannedSql      the scanned SQL with metadata
     * @param parsedStatement the JSqlParser parsed statement, may be null if parsing failed
     * @return list of violations (empty if no issues)
     */
    List<RuleViolation> analyze(ScannedSql scannedSql, Statement parsedStatement);
}
