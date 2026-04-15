package com.zhuangjie.sentinel.config;

import com.zhuangjie.sentinel.analyzer.MultiModelChatService;
import com.zhuangjie.sentinel.analyzer.MultiModelChatService.NamedChatClient;
import com.zhuangjie.sentinel.analyzer.advisor.AiLoggingAdvisor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 模型配置类。
 * <p>
 * 从 YAML 配置 {@code sentinel.ai.*} 读取多模型 provider 列表，
 * 为每个 provider 创建 OpenAiApi → OpenAiChatModel → ChatClient（附加 AiLoggingAdvisor），
 * 最终组装成 {@link MultiModelChatService} Bean 用于 round-robin 轮询调用。
 * <p>
 * 同时配置全局的 {@link RetryTemplate}，用于 AI 调用的指数退避重试。
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "sentinel.ai")
public class AiConfig {

    private boolean enabled;
    private String apiKey;
    private float temperature = 0.2f;
    /** 重试最大次数（所有模型都失败后的整体重试） */
    private int retryMaxAttempts = 3;
    private long retryInitialInterval = 2000;
    private double retryMultiplier = 2.0;
    private long retryMaxInterval = 15000;
    /** 多模型 provider 配置列表 */
    private List<ProviderConfig> providers = new ArrayList<>();

    /**
     * 创建多模型调用服务 Bean。
     * 遍历 providers 配置，为每个模型创建独立的 ChatClient。
     */
    @Bean
    @ConditionalOnProperty(name = "sentinel.ai.enabled", havingValue = "true")
    public MultiModelChatService multiModelChatService() {
        List<NamedChatClient> clients = new ArrayList<>();

        if (providers.isEmpty()) {
            log.warn("No AI providers configured under sentinel.ai.providers, using fallback");
            clients.add(createNamedClient("fallback", "qwen3.5-plus",
                    "https://dashscope.aliyuncs.com/compatible-mode", apiKey, temperature));
        } else {
            for (ProviderConfig cfg : providers) {
                String effectiveKey = cfg.getApiKey() != null && !cfg.getApiKey().isBlank()
                        ? cfg.getApiKey() : apiKey;
                float effectiveTemp = cfg.getTemperature() != null ? cfg.getTemperature() : temperature;
                clients.add(createNamedClient(cfg.getName(), cfg.getModel(),
                        cfg.getBaseUrl(), effectiveKey, effectiveTemp));
            }
        }

        log.info("Configured {} AI model providers for ChatClient rotation", clients.size());
        return new MultiModelChatService(clients);
    }

    /**
     * 全局重试模板 Bean（指数退避策略）。
     * 由 AiSqlAnalyzer 注入使用，在所有模型 fallback 均失败后进行整体重试。
     */
    @Bean
    public RetryTemplate retryTemplate() {
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(retryMaxAttempts);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(retryInitialInterval);
        backOffPolicy.setMultiplier(retryMultiplier);
        backOffPolicy.setMaxInterval(retryMaxInterval);

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);

        log.info("AI RetryTemplate configured: maxAttempts={}, initialInterval={}ms, multiplier={}, maxInterval={}ms",
                retryMaxAttempts, retryInitialInterval, retryMultiplier, retryMaxInterval);
        return template;
    }

    /**
     * 为单个 provider 创建完整的 ChatClient 链：
     * OpenAiApi（HTTP 客户端）→ OpenAiChatModel（模型层，禁用内部重试）→
     * ChatClient（附加 AiLoggingAdvisor 日志记录）。
     * <p>
     * 注入 maxAttempts=1 的 RetryTemplate 到 OpenAiChatModel，
     * 禁用其内部重试，由外层 MultiModelChatService 负责模型间 fallback。
     */
    private NamedChatClient createNamedClient(String name, String model, String baseUrl,
                                               String key, float temp) {
        var openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .build();

        // 禁用 OpenAiChatModel 内部重试，让失败立即上抛给 MultiModelChatService 做 fallback
        var noRetry = new RetryTemplate();
        noRetry.setRetryPolicy(new SimpleRetryPolicy(1));

        var chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature((double) temp)
                        .build())
                .retryTemplate(noRetry)
                .build();

        var chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new AiLoggingAdvisor(name))
                .build();

        return new NamedChatClient(name, model, chatClient);
    }

    /** 单个模型 provider 的配置 */
    @Data
    public static class ProviderConfig {
        private String name;
        private String model;
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        private String apiKey;
        private Float temperature;
    }
}
