package com.zhuangjie.sentinel.config;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.zhuangjie.sentinel.analyzer.ModelRouter;
import com.zhuangjie.sentinel.analyzer.provider.ModelProvider;
import com.zhuangjie.sentinel.analyzer.provider.OpenAiCompatProvider;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "sentinel.ai")
public class AiConfig {

    private boolean enabled;
    private String apiKey;
    private float temperature = 0.2f;
    private List<ProviderConfig> providers = new ArrayList<>();

    /**
     * Kept for backward compatibility — DashScope SDK is still used by RAG embedding.
     */
    @Bean
    @ConditionalOnProperty(name = "sentinel.ai.enabled", havingValue = "true")
    public MultiModalConversation multiModalConversation() {
        return new MultiModalConversation();
    }

    @Bean
    @ConditionalOnProperty(name = "sentinel.ai.enabled", havingValue = "true")
    public ModelRouter modelRouter() {
        if (providers.isEmpty()) {
            log.warn("No AI providers configured under sentinel.ai.providers, AI calls will fail");
            return new ModelRouter(List.of(
                    new OpenAiCompatProvider("fallback", "qwen3.5-plus",
                            "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            apiKey, temperature)
            ));
        }

        List<ModelProvider> modelProviders = new ArrayList<>();
        for (ProviderConfig cfg : providers) {
            String effectiveKey = cfg.getApiKey() != null && !cfg.getApiKey().isBlank()
                    ? cfg.getApiKey() : apiKey;
            float effectiveTemp = cfg.getTemperature() != null ? cfg.getTemperature() : temperature;

            modelProviders.add(new OpenAiCompatProvider(
                    cfg.getName(), cfg.getModel(), cfg.getBaseUrl(),
                    effectiveKey, effectiveTemp));
        }

        log.info("Configured {} AI model providers for rotation", modelProviders.size());
        return new ModelRouter(modelProviders);
    }

    @Data
    public static class ProviderConfig {
        private String name;
        private String model;
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey;
        private Float temperature;
    }
}
