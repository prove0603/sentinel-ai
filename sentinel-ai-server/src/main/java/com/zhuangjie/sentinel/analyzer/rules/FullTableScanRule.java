package com.zhuangjie.sentinel.analyzer.rules;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects SELECT/UPDATE/DELETE statements without a WHERE clause, which leads to full table scans.
 */
public record FullTableScanRule() implements SqlRule {

    @Override
    public String name() {
        return "FullTableScan";
    }

    @Override
    public String description() {
        return "检测缺少 WHERE 条件的查询，可能导致全表扫描";
    }

    @Override
    public List<RuleViolation> analyze(ScannedSql scannedSql, Statement parsedStatement) {
        if (parsedStatement == null) {
            return List.of();
        }

        List<RuleViolation> violations = new ArrayList<>();

        switch (parsedStatement) {
            case PlainSelect ps -> {
                if (ps.getWhere() == null) {
                    violations.add(new RuleViolation(
                            name(), RiskLevel.P0,
                            "SELECT 语句缺少 WHERE 条件，将导致全表扫描",
                            "SQL: " + truncate(scannedSql.sqlNormalized(), 200)
                    ));
                }
            }
            case Update upd -> {
                if (upd.getWhere() == null) {
                    violations.add(new RuleViolation(
                            name(), RiskLevel.P0,
                            "UPDATE 语句缺少 WHERE 条件，将更新全表数据",
                            "SQL: " + truncate(scannedSql.sqlNormalized(), 200)
                    ));
                }
            }
            case Delete del -> {
                if (del.getWhere() == null) {
                    violations.add(new RuleViolation(
                            name(), RiskLevel.P0,
                            "DELETE 语句缺少 WHERE 条件，将删除全表数据",
                            "SQL: " + truncate(scannedSql.sqlNormalized(), 200)
                    ));
                }
            }
            default -> { /* INSERT or others — not applicable */ }
        }

        return violations;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
