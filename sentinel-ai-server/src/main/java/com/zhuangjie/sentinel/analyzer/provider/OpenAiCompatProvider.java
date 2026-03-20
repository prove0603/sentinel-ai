package com.zhuangjie.sentinel.analyzer.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls any OpenAI-compatible chat/completions endpoint.
 * Works with DashScope compatible-mode, OpenAI, and other providers.
 */
@Slf4j
public class OpenAiCompatProvider implements ModelProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String providerName;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final float temperature;
    private final HttpClient httpClient;

    public OpenAiCompatProvider(String providerName, String model, String baseUrl,
                                 String apiKey, float temperature) {
        this.providerName = providerName;
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return providerName;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public ModelResponse call(String systemPrompt, String userPrompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", temperature,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String jsonBody = MAPPER.writeValueAsString(body);
        String endpoint = baseUrl + "/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        log.debug("[{}] Calling {} with model={}", providerName, endpoint, model);

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from " + providerName
                    + ": " + response.body().substring(0, Math.min(500, response.body().length())));
        }

        JsonNode root = MAPPER.readTree(response.body());
        String content = root.at("/choices/0/message/content").asText();
        int inputTokens = root.at("/usage/prompt_tokens").asInt(0);
        int outputTokens = root.at("/usage/completion_tokens").asInt(0);

        log.debug("[{}] Response received: model={}, tokens={}", providerName, model, inputTokens + outputTokens);
        return new ModelResponse(content, model, inputTokens + outputTokens);
    }
}
