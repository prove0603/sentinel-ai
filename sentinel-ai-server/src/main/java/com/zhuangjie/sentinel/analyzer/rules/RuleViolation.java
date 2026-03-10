package com.zhuangjie.sentinel.analyzer.rules;

import com.zhuangjie.sentinel.pojo.enums.RiskLevel;

/**
 * A single issue found by a rule during SQL analysis.
 *
 * @param ruleName  the rule that detected this issue
 * @param riskLevel severity level
 * @param message   short description
 * @param detail    detailed explanation or suggestion
 */
public record RuleViolation(
        String ruleName,
        RiskLevel riskLevel,
        String message,
        String detail
) {
}
