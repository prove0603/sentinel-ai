package com.zhuangjie.sentinel.analyzer;

import com.zhuangjie.sentinel.analyzer.MultiModelChatService.AiCallResult;
import com.zhuangjie.sentinel.knowledge.KnowledgeContextBuilder;
import com.zhuangjie.sentinel.knowledge.TableNameExtractor;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import com.zhuangjie.sentinel.rag.KnowledgeRagService;
import com.zhuangjie.sentinel.service.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * AI SQL 性能分析器。
 * <p>
 * 核心职责：接收扫描到的 SQL，构建 AI 分析 prompt（注入 DDL 表结构 + RAG 业务知识），
 * 调用 {@link MultiModelChatService} 进行多模型轮询分析，返回结构化的风险评估结果。
 * <p>
 * 缓存策略：Caffeine 内存缓存（key=sqlHash），避免同一 SQL 重复调用 AI。
 * 重试策略：通过 Spring Retry 的 {@link RetryTemplate} 实现指数退避重试。
 */
@Slf4j
@Component
public class AiSqlAnalyzer {

    private static final String CACHE_NAME = "ai-sql-analysis";

    /** Prompt 模板 key 常量 */
    public static final String KEY_SYSTEM_PROMPT = "SQL_ANALYSIS_SYSTEM";
    public static final String KEY_USER_PROMPT = "SQL_ANALYSIS_USER";

    /** 默认系统提示词（DB 中无配置时的 fallback） */
    static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个资深的 MySQL DBA，拥有 10 年以上数据库性能优化经验。
            你的任务是分析 SQL 语句的性能风险，给出准确的判断和可执行的优化建议。
            你需要结合表结构、索引信息、数据量来做出判断。
            分析时请使用中文回复。
            """;

    /** 默认用户提示词模板（DB 中无配置时的 fallback）：%s 占位符分别为 SQL类型、来源位置、SQL文本、DDL上下文、RAG上下文 */
    static final String DEFAULT_USER_PROMPT = """
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
    private final RetryTemplate retryTemplate;
    private final PromptTemplateService promptTemplateService;

    public AiSqlAnalyzer(@Autowired(required = false) MultiModelChatService chatService,
                          CacheManager cacheManager,
                          @Autowired(required = false) KnowledgeContextBuilder knowledgeContextBuilder,
                          @Autowired(required = false) KnowledgeRagService knowledgeRagService,
                          @Autowired(required = false) TableNameExtractor tableNameExtractor,
                          RetryTemplate retryTemplate,
                          PromptTemplateService promptTemplateService) {
        this.chatService = chatService;
        this.cacheManager = cacheManager;
        this.knowledgeContextBuilder = knowledgeContextBuilder;
        this.knowledgeRagService = knowledgeRagService;
        this.tableNameExtractor = tableNameExtractor;
        this.retryTemplate = retryTemplate;
        this.promptTemplateService = promptTemplateService;
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

    /**
     * 分析单条 SQL 的性能风险。
     * <p>
     * 流程：缓存检查 → 构建 DDL 表结构上下文 → 构建 RAG 业务知识上下文 →
     * 组装完整 prompt → 调用 AI（含重试） → 缓存结果并返回。
     *
     * @param sqlHash     SQL 标准化后的哈希值（用于缓存 key 和去重）
     * @param sql         扫描到的 SQL 信息
     * @param projectName 所属项目名称（用于 DDL 上下文构建）
     * @return AI 分析详情，分析失败返回 null
     */
    public AiAnalysisDetail analyze(String sqlHash, ScannedSql sql, String projectName) {
        if (!isAvailable()) {
            return null;
        }

        // 1. Caffeine 缓存检查：同一 SQL 不重复调用 AI
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            AiAnalysisDetail cached = cache.get(sqlHash, AiAnalysisDetail.class);
            if (cached != null) {
                log.debug("AI cache hit for sqlHash={}", sqlHash);
                return cached;
            }
        }

        // 2. 构建 DDL 表结构上下文：从 table-meta 目录读取 SQL 引用的表的 DDL 文件
        String tableContext = "";
        if (knowledgeContextBuilder != null && projectName != null) {
            try {
                tableContext = knowledgeContextBuilder.buildContext(sql.sqlNormalized());
            } catch (Exception e) {
                log.warn("Failed to build knowledge context: {}", e.getMessage());
            }
        }

        // 3. 构建 RAG 业务知识上下文：精确匹配表名 + 向量语义检索
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

        // 4. 从 DB 加载用户提示词模板（fallback 到默认值），组装完整 prompt 并调用 AI
        String userTemplate = promptTemplateService.getContent(KEY_USER_PROMPT);
        if (userTemplate == null) {
            userTemplate = DEFAULT_USER_PROMPT;
        }
        String promptText = String.format(userTemplate,
                sql.sqlType(), sql.sourceLocation(), sql.sqlNormalized(), tableContext, ragContext);

        AiAnalysisDetail detail = callWithRetry(promptText);

        if (detail != null && cache != null) {
            cache.put(sqlHash, detail);
        }
        return detail;
    }

    /**
     * 带重试的 AI 调用。
     * RetryTemplate 负责指数退避重试，MultiModelChatService 负责模型间 fallback。
     * 两层配合：单次失败立即切模型，所有模型都失败后等待退避再重试一轮。
     */
    private AiAnalysisDetail callWithRetry(String promptText) {
        try {
            return retryTemplate.execute((RetryCallback<AiAnalysisDetail, Exception>) context -> {
                int attempt = context.getRetryCount() + 1;
                if (attempt > 1) {
                    log.info("AI retry attempt {}", attempt);
                }
                // 从 DB 加载系统提示词，fallback 到默认值
                String systemPrompt = promptTemplateService.getContent(KEY_SYSTEM_PROMPT);
                if (systemPrompt == null) {
                    systemPrompt = DEFAULT_SYSTEM_PROMPT;
                }
                AiCallResult<SqlRiskAssessment> result =
                        chatService.call(systemPrompt, promptText, SqlRiskAssessment.class);
                log.debug("AI analysis completed: riskLevel={}, model={}, tokens={}",
                        result.entity().riskLevel(), result.model(), result.tokensUsed());
                return new AiAnalysisDetail(result.entity(), result.model(), result.tokensUsed());
            });
        } catch (Exception e) {
            log.error("AI analysis failed after all retries: {}", e.getMessage());
            return null;
        }
    }

    /** AI 分析结果详情（风险评估 + 使用的模型 + token 消耗） */
    public record AiAnalysisDetail(
            SqlRiskAssessment assessment,
            String model,
            int tokensUsed
    ) {
    }
}
