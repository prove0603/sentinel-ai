package com.zhuangjie.sentinel.analyzer;

import com.zhuangjie.sentinel.analyzer.rules.RuleViolation;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;

import java.util.List;

/**
 * Aggregated result from running all rules on a single SQL statement.
 *
 * @param violations all violations found
 * @param riskLevel  the worst (highest severity) risk level among all violations
 */
public record AnalysisResult(
        List<RuleViolation> violations,
        RiskLevel riskLevel
) {
}
