package com.zhuangjie.sentinel.pojo.dto;

import java.util.List;

/**
 * Structured output from AI analysis. Spring AI maps the LLM response directly into this record.
 */
public record SqlRiskAssessment(
        String riskLevel,
        boolean canUseIndex,
        String indexUsed,
        long estimatedScanRows,
        int estimatedExecTimeMs,
        List<String> issues,
        List<String> indexSuggestions,
        List<String> rewriteSuggestions,
        String explanation
) {
}
