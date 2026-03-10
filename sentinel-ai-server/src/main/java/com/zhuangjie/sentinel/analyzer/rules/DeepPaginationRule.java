package com.zhuangjie.sentinel.analyzer.rules;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.List;

/**
 * Detects deep pagination patterns (large OFFSET) which degrade query performance.
 */
public record DeepPaginationRule() implements SqlRule {

    private static final long P2_THRESHOLD = 1_000;
    private static final long P1_THRESHOLD = 10_000;
    private static final long P0_THRESHOLD = 100_000;

    @Override
    public String name() {
        return "DeepPagination";
    }

    @Override
    public String description() {
        return "检测深度分页查询（大 OFFSET），随偏移量增大性能急剧下降";
    }

    @Override
    public List<RuleViolation> analyze(ScannedSql scannedSql, Statement parsedStatement) {
        if (!(parsedStatement instanceof PlainSelect ps)) {
            return List.of();
        }

        long offsetValue = extractOffset(ps);
        if (offsetValue < 0) {
            return List.of();
        }

        RiskLevel level;
        if (offsetValue >= P0_THRESHOLD) {
            level = RiskLevel.P0;
        } else if (offsetValue >= P1_THRESHOLD) {
            level = RiskLevel.P1;
        } else if (offsetValue >= P2_THRESHOLD) {
            level = RiskLevel.P2;
        } else {
            return List.of();
        }

        return List.of(new RuleViolation(
                name(), level,
                "深度分页: OFFSET = " + offsetValue + "，性能随偏移量增大急剧下降",
                "建议使用游标分页（WHERE id > lastId LIMIT N）替代 OFFSET 分页"
        ));
    }

    private long extractOffset(PlainSelect ps) {
        Offset offset = ps.getOffset();
        if (offset != null && offset.getOffset() instanceof LongValue lv) {
            return lv.getValue();
        }

        Limit limit = ps.getLimit();
        if (limit != null && limit.getOffset() != null
                && limit.getOffset() instanceof LongValue lv) {
            return lv.getValue();
        }

        return -1;
    }
}
