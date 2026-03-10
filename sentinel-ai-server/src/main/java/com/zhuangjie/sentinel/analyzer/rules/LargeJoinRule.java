package com.zhuangjie.sentinel.analyzer.rules;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.List;

/**
 * Detects SELECT statements that JOIN too many tables, increasing query complexity.
 */
public record LargeJoinRule() implements SqlRule {

    private static final int WARN_THRESHOLD = 3;
    private static final int CRITICAL_THRESHOLD = 5;

    @Override
    public String name() {
        return "LargeJoin";
    }

    @Override
    public String description() {
        return "检测多表 JOIN 查询，JOIN 表数过多会增加查询复杂度";
    }

    @Override
    public List<RuleViolation> analyze(ScannedSql scannedSql, Statement parsedStatement) {
        if (!(parsedStatement instanceof PlainSelect ps)) {
            return List.of();
        }

        List<Join> joins = ps.getJoins();
        if (joins == null || joins.isEmpty()) {
            return List.of();
        }

        int joinCount = joins.size();
        int tableCount = joinCount + 1;

        if (tableCount >= CRITICAL_THRESHOLD) {
            return List.of(new RuleViolation(
                    name(), RiskLevel.P1,
                    "查询涉及 " + tableCount + " 张表的 JOIN，性能风险较高",
                    "建议拆分为多次简单查询或使用中间表/冗余字段减少 JOIN 数量"
            ));
        }

        if (tableCount >= WARN_THRESHOLD) {
            return List.of(new RuleViolation(
                    name(), RiskLevel.P2,
                    "查询涉及 " + tableCount + " 张表的 JOIN，需关注性能",
                    "建议确认各 JOIN 条件都有合适索引，必要时考虑拆分查询"
            ));
        }

        return List.of();
    }
}
