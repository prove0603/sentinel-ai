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

    private NamedChatClient createNamedClient(String name, String model, String baseUrl,
                                               String key, float temp) {
        var openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .build();

        var chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature((double) temp)
                        .build())
                .build();

        var chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new AiLoggingAdvisor(name))
                .build();

        return new NamedChatClient(name, model, chatClient);
    }

    @Data
    public static class ProviderConfig {
        private String name;
        private String model;
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        private String apiKey;
        private Float temperature;
    }
}
