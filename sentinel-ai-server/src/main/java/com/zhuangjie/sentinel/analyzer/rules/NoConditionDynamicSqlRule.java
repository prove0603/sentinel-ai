package com.zhuangjie.sentinel.analyzer.rules;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;

/**
 * Detects dynamic SQL where all conditions could be absent at runtime,
 * leading to a query without any WHERE clause (full table scan).
 * <p>
 * Only triggers when the SQL was expanded from dynamic SQL (hasDynamicSql=true)
 * AND the resulting query has no WHERE clause.
 */
public record NoConditionDynamicSqlRule() implements SqlRule {

    @Override
    public String name() {
        return "NoConditionDynamicSql";
    }

    @Override
    public String description() {
        return "检测动态 SQL 中所有条件可能为空的情况，运行时可能退化为全表扫描";
    }

    @Override
    public List<RuleViolation> analyze(ScannedSql scannedSql, Statement parsedStatement) {
        if (!scannedSql.hasDynamicSql() || parsedStatement == null) {
            return List.of();
        }

        boolean hasNoWhere = switch (parsedStatement) {
            case PlainSelect ps -> ps.getWhere() == null;
            case Update upd -> upd.getWhere() == null;
            case Delete del -> del.getWhere() == null;
            default -> false;
        };

        if (!hasNoWhere) {
            return List.of();
        }

        String sqlType = scannedSql.sqlType();
        return List.of(new RuleViolation(
                name(), RiskLevel.P0,
                "动态 SQL 的 " + sqlType + " 语句在所有条件为空时无 WHERE 子句",
                "来源: " + scannedSql.sourceLocation() +
                        "，建议为动态 SQL 添加兜底条件（如 1=1 AND ...）或业务层参数校验"
        ));
    }
}
