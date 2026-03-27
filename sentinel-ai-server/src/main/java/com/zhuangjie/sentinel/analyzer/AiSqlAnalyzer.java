package com.zhuangjie.sentinel.analyzer;

import com.zhuangjie.sentinel.analyzer.MultiModelChatService.AiCallResult;
import com.zhuangjie.sentinel.knowledge.KnowledgeContextBuilder;
import com.zhuangjie.sentinel.knowledge.TableNameExtractor;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import com.zhuangjie.sentinel.rag.KnowledgeRagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * AI-powered SQL performance analysis.
 * Uses {@link MultiModelChatService} for round-robin multi-model rotation with automatic fallback.
 * Structured output is handled by Spring AI's {@link org.springframework.ai.converter.BeanOutputConverter}.
 * Caffeine cache prevents redundant calls for the same SQL hash.
 */
@Slf4j
@Component
public class AiSqlAnalyzer {

    private static final String CACHE_NAME = "ai-sql-analysis";
    private static final int MAX_RETRIES = 1;

    static final String SYSTEM_PROMPT = """
            你是一个资深的 MySQL DBA，拥有 10 年以上数据库性能优化经验。
            你的任务是分析 SQL 语句的性能风险，给出准确的判断和可执行的优化建议。
            你需要结合表结构、索引信息、数据量来做出判断。
            分析时请使用中文回复。
            """;

    private static final String PROMPT_TEMPLATE = """
            请对以下 SQL 语句进行性能风险分析。

            ## SQL 信息
            - **SQL 类型：** %s
            - **来源位置：** %s
            - **SQL 文本：**
            ```sql
            %s
            ```

            %s

            %s

            ## 分析要求
            1. 给出风险等级（riskLevel）：P0（紧急-必定慢SQL）/ P1（高危）/ P2（中危）/ P3（低危）/ P4（安全）
            2. 判断是否能使用索引（canUseIndex），若能使用则说明预计使用哪个索引（indexUsed）
            3. 估算扫描行数（estimatedScanRows）和执行时间（estimatedExecTimeMs，单位毫秒）
            4. 列出所有性能问题（issues 数组），包括但不限于：
               - 是否走索引、是否全表扫描
               - LIKE 前导通配符
               - 函数包裹列导致索引失效
               - 隐式类型转换
               - 多表 JOIN 性能问题
               - 深分页问题
               - 动态 SQL 所有条件为空导致全表扫描的风险
               - 其他任何 MySQL 性能问题
            5. 给出索引创建建议（indexSuggestions 数组，用 SQL 语句表示）
            6. 给出 SQL 改写建议（rewriteSuggestions 数组，用改写后的 SQL 表示）
            7. 给出综合分析说明（explanation）
            """;

    private final MultiModelChatService chatService;
    private final CacheManager cacheManager;
    private final KnowledgeContextBuilder knowledgeContextBuilder;
    private final KnowledgeRagService knowledgeRagService;
    private final TableNameExtractor tableNameExtractor;

    public AiSqlAnalyzer(@Autowired(required = false) MultiModelChatService chatService,
                          CacheManager cacheManager,
                          @Autowired(required = false) KnowledgeContextBuilder knowledgeContextBuilder,
                          @Autowired(required = false) KnowledgeRagService knowledgeRagService,
                          @Autowired(required = false) TableNameExtractor tableNameExtractor) {
        this.chatService = chatService;
        this.cacheManager = cacheManager;
        this.knowledgeContextBuilder = knowledgeContextBuilder;
        this.knowledgeRagService = knowledgeRagService;
        this.tableNameExtractor = tableNameExtractor;
        if (chatService == null) {
            log.info("AI analysis disabled: MultiModelChatService not configured. Set sentinel.ai.enabled=true and configure providers.");
        } else {
            log.info("AI analysis enabled with {} providers, DDL context: {}, RAG: {}",
                    chatService.getClients().size(),
                    knowledgeContextBuilder != null && knowledgeContextBuilder.isAvailable() ? "available" : "unavailable",
                    knowledgeRagService != null && knowledgeRagService.isRagAvailable() ? "available" : "unavailable");
        }
    }

    public boolean isAvailable() {
        return chatService != null;
    }

    public AiAnalysisDetail analyze(String sqlHash, ScannedSql sql, String projectName) {
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

        String tableContext = "";
        if (knowledgeContextBuilder != null && projectName != null) {
            try {
                tableContext = knowledgeContextBuilder.buildContext(sql.sqlNormalized());
            } catch (Exception e) {
                log.warn("Failed to build knowledge context: {}", e.getMessage());
            }
        }

        String ragContext = "";
        if (knowledgeRagService != null) {
            try {
                Set<String> tableNames = tableNameExtractor != null
                        ? tableNameExtractor.extract(sql.sqlNormalized())
                        : Set.of();
                ragContext = knowledgeRagService.retrieveContext(sql.sqlNormalized(), tableNames);
            } catch (Exception e) {
                log.warn("Failed to retrieve RAG context: {}", e.getMessage());
            }
        }

        log.debug("AI analyze: sqlHash={}, tableContext={}, ragContext={}", sqlHash,
                tableContext.isBlank() ? "EMPTY" : "HAS DDL (" + tableContext.length() + " chars)",
                ragContext.isBlank() ? "EMPTY" : "HAS RAG (" + ragContext.length() + " chars)");

        String promptText = String.format(PROMPT_TEMPLATE,
                sql.sqlType(), sql.sourceLocation(), sql.sqlNormalized(), tableContext, ragContext);

        AiAnalysisDetail detail = callWithRetry(promptText);

        if (detail != null && cache != null) {
            cache.put(sqlHash, detail);
        }
        return detail;
    }

    private AiAnalysisDetail callWithRetry(String promptText) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                AiCallResult<SqlRiskAssessment> result =
                        chatService.call(SYSTEM_PROMPT, promptText, SqlRiskAssessment.class);

                log.debug("AI analysis completed: riskLevel={}, model={}, tokens={}",
                        result.entity().riskLevel(), result.model(), result.tokensUsed());
                return new AiAnalysisDetail(result.entity(), result.model(), result.tokensUsed());

            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("AI call failed (attempt {}/{}), retrying: {}",
                            attempt + 1, MAX_RETRIES + 1, e.getMessage());
                    sleep(1000L * (attempt + 1));
                } else {
                    log.error("AI analysis failed after {} attempts: {}", MAX_RETRIES + 1, e.getMessage());
                }
            }
        }
        return null;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record AiAnalysisDetail(
            SqlRiskAssessment assessment,
            String model,
            int tokensUsed
    ) {
    }
}
