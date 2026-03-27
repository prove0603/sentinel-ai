package com.zhuangjie.sentinel.analyzer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes AI calls across multiple Spring AI ChatClient instances using round-robin.
 * On failure, automatically falls back to the next provider.
 * <p>
 * Uses {@link BeanOutputConverter} for structured output format instructions and parsing.
 * Each ChatClient has an {@link com.zhuangjie.sentinel.analyzer.advisor.AiLoggingAdvisor}
 * attached for per-call logging.
 */
@Slf4j
public class MultiModelChatService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final List<NamedChatClient> clients;
    private final AtomicInteger counter = new AtomicInteger(0);

    public record NamedChatClient(String name, String model, ChatClient chatClient) {}

    public record AiCallResult<T>(T entity, String model, int tokensUsed) {}

    public MultiModelChatService(List<NamedChatClient> clients) {
        if (clients == null || clients.isEmpty()) {
            throw new IllegalArgumentException("At least one ChatClient is required");
        }
        this.clients = List.copyOf(clients);
        log.info("MultiModelChatService initialized with {} providers: {}", clients.size(),
                clients.stream().map(c -> c.name() + "(" + c.model() + ")").toList());
    }

    /**
     * Calls the AI with structured output. Appends {@link BeanOutputConverter} format instructions
     * to the user prompt, then parses the model's JSON response into the target type.
     * Round-robin across providers with automatic fallback.
     */
    public <T> AiCallResult<T> call(String systemPrompt, String userPrompt, Class<T> responseType) throws Exception {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(responseType);
        String enhancedUserPrompt = userPrompt + "\n\n" + converter.getFormat();

        int total = clients.size();
        int startIdx = Math.floorMod(counter.getAndIncrement(), total);
        Exception lastException = null;

        for (int i = 0; i < total; i++) {
            int idx = (startIdx + i) % total;
            NamedChatClient client = clients.get(idx);

            try {
                ChatResponse response = client.chatClient().prompt()
                        .system(systemPrompt)
                        .user(enhancedUserPrompt)
                        .call()
                        .chatResponse();

                String content = response.getResult().getOutput().getText();
                String cleanJson = content
                        .replaceAll("(?s)```json\\s*", "")
                        .replaceAll("(?s)```\\s*", "")
                        .trim();

                T entity = converter.convert(cleanJson);

                int tokensUsed = extractTokens(response);

                if (i > 0) {
                    log.info("Fallback succeeded: {} (after {} failed attempts)", client.name(), i);
                }
                return new AiCallResult<>(entity, client.model(), tokensUsed);

            } catch (Exception e) {
                lastException = e;
                log.warn("Provider {} failed: {}. {}", client.name(), e.getMessage(),
                        i < total - 1 ? "Trying next provider..." : "No more providers to try.");
            }
        }

        throw new RuntimeException("All " + total + " providers failed", lastException);
    }

    /**
     * Simple call returning raw content string. Used by test/tool endpoints.
     */
    public RawCallResult callRaw(String systemPrompt, String userPrompt) throws Exception {
        int total = clients.size();
        int startIdx = Math.floorMod(counter.getAndIncrement(), total);
        Exception lastException = null;

        for (int i = 0; i < total; i++) {
            int idx = (startIdx + i) % total;
            NamedChatClient client = clients.get(idx);

            try {
                ChatResponse response = client.chatClient().prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .chatResponse();

                String content = response.getResult().getOutput().getText();
                int tokensUsed = extractTokens(response);
                return new RawCallResult(content, client.model(), tokensUsed);

            } catch (Exception e) {
                lastException = e;
                log.warn("Provider {} failed: {}", client.name(), e.getMessage());
            }
        }
        throw new RuntimeException("All " + total + " providers failed", lastException);
    }

    public record RawCallResult(String content, String model, int tokensUsed) {}

    public NamedChatClient peekNext() {
        int idx = Math.floorMod(counter.get(), clients.size());
        return clients.get(idx);
    }

    public List<NamedChatClient> getClients() {
        return clients;
    }

    public int getCallCount() {
        return counter.get();
    }

    private int extractTokens(ChatResponse response) {
        try {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                Long total = response.getMetadata().getUsage().getTotalTokens();
                return total != null ? total.intValue() : 0;
            }
        } catch (Exception e) {
            log.debug("Failed to extract token usage: {}", e.getMessage());
        }
        return 0;
    }
}
