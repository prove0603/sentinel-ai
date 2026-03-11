package com.zhuangjie.sentinel.analyzer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuangjie.sentinel.analyzer.rules.RuleViolation;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI-powered deep SQL analysis using Spring AI Alibaba (DashScope / Qwen).
 * <p>
 * Strategy: rule pre-filter → Caffeine cache → model call → structured JSON output.
 * Only invoked for SQL already flagged P0/P1/P2 by the rule engine.
 */
@Slf4j
@Component
public class AiSqlAnalyzer {

    private static final String CACHE_NAME = "ai-sql-analysis";
    private static final int MAX_RETRIES = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String PROMPT_TEMPLATE = """
            请对以下 SQL 语句进行 MySQL 性能风险分析。

            ## SQL 信息
            - **SQL 类型：** %s
            - **来源位置：** %s
            - **SQL 文本：**
            ```sql
            %s
            ```

            ## 规则引擎已发现的问题
            %s

            ## 分析要求
            1. 给出风险等级：P0（紧急-必定慢SQL）/ P1（高危）/ P2（中危）/ P3（低危）/ P4（安全）
            2. 判断是否能使用索引，若能使用则说明预计使用哪个索引
            3. 估算扫描行数（estimatedScanRows）和执行时间（estimatedExecTimeMs，单位毫秒）
            4. 列出所有性能问题（issues 数组）
            5. 给出索引创建建议（indexSuggestions 数组，用 SQL 语句表示）
            6. 给出 SQL 改写建议（rewriteSuggestions 数组，用改写后的 SQL 表示）
            7. 给出综合分析说明（explanation）

            ## 响应格式
            请严格以如下 JSON 格式回复，不要包含 markdown 代码块标记或其他内容：
            {
              "riskLevel": "P0~P4之一",
              "canUseIndex": true或false,
              "indexUsed": "预计使用的索引名，无则为null",
              "estimatedScanRows": 数字,
              "estimatedExecTimeMs": 数字,
              "issues": ["问题1", "问题2"],
              "indexSuggestions": ["CREATE INDEX idx_xxx ON table(col)"],
              "rewriteSuggestions": ["改写后的SQL"],
              "explanation": "综合分析说明"
            }
            """;

    private final ChatClient chatClient;
    private final CacheManager cacheManager;

    @Value("${sentinel.ai.model:qwen3.5-plus}")
    private String model;

    public AiSqlAnalyzer(@Autowired(required = false) @Qualifier("sqlAnalysisChatClient") ChatClient chatClient,
                          CacheManager cacheManager) {
        this.chatClient = chatClient;
        this.cacheManager = cacheManager;
        if (chatClient == null) {
            log.info("AI analysis disabled: ChatClient not configured. Set sentinel.ai.enabled=true and AI_DASHSCOPE_API_KEY to enable.");
        } else {
            log.info("AI analysis enabled");
        }
    }

    public boolean isAvailable() {
        return chatClient != null;
    }

    /**
     * Analyzes a SQL statement using AI. Returns null if AI is unavailable or analysis fails.
     *
     * @param sqlHash    normalized SQL hash (used as cache key)
     * @param sql        the scanned SQL
     * @param ruleResult the rule engine result for context
     * @return AI assessment, or null on failure
     */
    public AiAnalysisDetail analyze(String sqlHash, ScannedSql sql, AnalysisResult ruleResult) {
        if (!isAvailable()) {
            return null;
        }

        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            AiAnalysisDetail cached = cache.get(sqlHash, AiAnalysisDetail.class);
            if (cached != null) {
                log.debug("AI cache hit for sqlHash={}", sqlHash);
                return cached;
            }
        }

        String ruleIssues = ruleResult.violations().stream()
                .map(RuleViolation::message)
                .map(msg -> "- " + msg)
                .collect(Collectors.joining("\n"));
        if (ruleIssues.isBlank()) {
            ruleIssues = "无";
        }

        String promptText = String.format(PROMPT_TEMPLATE,
                sql.sqlType(), sql.sourceLocation(), sql.sqlNormalized(), ruleIssues);

        AiAnalysisDetail detail = callWithRetry(promptText);

        if (detail != null && cache != null) {
            cache.put(sqlHash, detail);
        }
        return detail;
    }

    private AiAnalysisDetail callWithRetry(String promptText) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                ChatResponse response = chatClient.prompt()
                        .user(promptText)
                        .call()
                        .chatResponse();

                String content = response.getResult().getOutput().getText();
                SqlRiskAssessment assessment = parseResponse(content);

                int tokensUsed = 0;
                if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    tokensUsed = (int) response.getMetadata().getUsage().getTotalTokens();
                }

                log.debug("AI analysis completed: riskLevel={}, tokens={}", assessment.riskLevel(), tokensUsed);
                return new AiAnalysisDetail(assessment, model, tokensUsed);

            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("AI call failed (attempt {}/{}), retrying: {}", attempt + 1, MAX_RETRIES + 1, e.getMessage());
                    sleep(1000L * (attempt + 1));
                } else {
                    log.error("AI analysis failed after {} attempts: {}", MAX_RETRIES + 1, e.getMessage());
                }
            }
        }
        return null;
    }

    private SqlRiskAssessment parseResponse(String content) throws Exception {
        String json = content
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        log.debug("AI raw response: {}", json);
        return MAPPER.readValue(json, SqlRiskAssessment.class);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Wraps the AI assessment result with metadata (model name, tokens used).
     */
    public record AiAnalysisDetail(
            SqlRiskAssessment assessment,
            String model,
            int tokensUsed
    ) {
    }
}
