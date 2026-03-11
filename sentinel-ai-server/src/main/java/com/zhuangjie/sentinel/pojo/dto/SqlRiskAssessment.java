package com.zhuangjie.sentinel.pojo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structured output from AI analysis, deserialized from the LLM's JSON response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
