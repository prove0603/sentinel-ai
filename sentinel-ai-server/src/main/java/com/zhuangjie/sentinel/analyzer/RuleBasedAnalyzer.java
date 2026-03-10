package com.zhuangjie.sentinel.analyzer;

import com.zhuangjie.sentinel.analyzer.rules.*;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orchestrates all SQL rules against a scanned SQL statement.
 * Synchronous, millisecond-level analysis — no AI calls involved.
 */
@Slf4j
@Component
public class RuleBasedAnalyzer {

    private final List<SqlRule> rules;

    public RuleBasedAnalyzer() {
        this.rules = List.of(
                new FullTableScanRule(),
                new LikeLeadingWildcardRule(),
                new ImplicitConversionRule(),
                new LargeJoinRule(),
                new DeepPaginationRule(),
                new NoConditionDynamicSqlRule()
        );
    }

    /**
     * Runs all rules against the given scanned SQL and returns aggregated results.
     */
    public AnalysisResult analyze(ScannedSql scannedSql) {
        Statement statement = tryParse(scannedSql.sqlNormalized());

        List<RuleViolation> violations = new ArrayList<>();
        for (SqlRule rule : rules) {
            try {
                violations.addAll(rule.analyze(scannedSql, statement));
            } catch (Exception e) {
                log.warn("Rule {} failed on SQL [{}]: {}",
                        rule.name(), truncate(scannedSql.sqlNormalized(), 80), e.getMessage());
            }
        }

        RiskLevel worstLevel = violations.stream()
                .map(RuleViolation::riskLevel)
                .min(Comparator.comparingInt(Enum::ordinal))
                .orElse(RiskLevel.P4);

        return new AnalysisResult(violations, worstLevel);
    }

    private Statement tryParse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            log.debug("JSqlParser could not parse SQL: {}", truncate(sql, 100));
            return null;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
