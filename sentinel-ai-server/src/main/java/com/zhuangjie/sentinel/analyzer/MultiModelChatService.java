package com.zhuangjie.sentinel.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多模型 AI 调用服务。
 * <p>
 * 持有多个 Spring AI ChatClient 实例，通过 round-robin 轮询分发请求。
 * 当某个模型调用失败时，自动 fallback 到下一个可用模型。
 * 内置简易熔断机制：连续失败达到阈值后，该模型进入冷却期暂停使用。
 * <p>
 * 泛型方法 {@link #call} 使用 {@link BeanOutputConverter} 自动生成 JSON Schema 格式指令
 * 并将 LLM 响应解析为指定的 Java 类型（结构化输出）。
 */
@Slf4j
public class MultiModelChatService {

    /** 连续失败次数阈值，超过后触发冷却 */
    private static final int FAILURE_THRESHOLD = 3;
    /** 冷却时间（毫秒），冷却期内跳过该模型 */
    private static final long COOLDOWN_MS = 60_000;

    private final List<NamedChatClient> clients;
    /** 轮询计数器，用于 round-robin 选择下一个模型 */
    private final AtomicInteger counter = new AtomicInteger(0);
    /** 各模型的健康状态（连续失败次数 + 最后失败时间） */
    private final Map<String, ProviderHealth> healthMap = new ConcurrentHashMap<>();

    /** 命名的 ChatClient 封装（名称、模型标识、ChatClient 实例） */
    public record NamedChatClient(String name, String model, ChatClient chatClient) {}

    /** AI 调用结果封装（解析后的实体、实际使用的模型、消耗的 token 数） */
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
     * 结构化调用：发送 system/user prompt 到 LLM，自动注入 JSON Schema 格式指令，
     * 将响应解析为 responseType 指定的 Java 类型。
     * <p>
     * 调用流程：round-robin 选起始模型 → 跳过冷却中的模型 → 调用 → 失败则 fallback 下一个 →
     * 所有模型都在冷却中则重置健康状态后重试。
     *
     * @param <T>          目标类型（如 SqlRiskAssessment）
     * @param systemPrompt 系统提示词（角色设定）
     * @param userPrompt   用户提示词（具体任务）
     * @param responseType 期望的返回类型 Class
     * @return 包含解析实体、模型名、token 用量的结果
     */
    public <T> AiCallResult<T> call(String systemPrompt, String userPrompt, Class<T> responseType) throws Exception {
        // BeanOutputConverter 根据 responseType 生成 JSON Schema 格式说明，追加到 userPrompt 末尾
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(responseType);
        String enhancedUserPrompt = userPrompt + "\n\n" + converter.getFormat();

        int total = clients.size();
        int startIdx = Math.floorMod(counter.getAndIncrement(), total);
        Exception lastException = null;
        int skipped = 0;

        // 从 startIdx 开始，尝试所有模型
        for (int i = 0; i < total; i++) {
            int idx = (startIdx + i) % total;
            NamedChatClient client = clients.get(idx);

            if (isInCooldown(client.name())) {
                log.debug("Skipping provider {} (in cooldown)", client.name());
                skipped++;
                continue;
            }

            try {
                ChatResponse response = client.chatClient().prompt()
                        .system(systemPrompt)
                        .user(enhancedUserPrompt)
                        .call()
                        .chatResponse();

                // 清理 LLM 可能包裹的 markdown 代码块标记
                String content = response.getResult().getOutput().getText();
                String cleanJson = content
                        .replaceAll("(?s)```json\\s*", "")
                        .replaceAll("(?s)```\\s*", "")
                        .trim();

                // 将 JSON 反序列化为目标类型
                T entity = converter.convert(cleanJson);
                int tokensUsed = extractTokens(response);

                recordSuccess(client.name());
                if (i > 0) {
                    log.info("Fallback succeeded: {} (after {} skipped/failed attempts)", client.name(), i);
                }
                return new AiCallResult<>(entity, client.model(), tokensUsed);

            } catch (Exception e) {
                lastException = e;
                recordFailure(client.name());
                log.warn("Provider {} failed (consecutive failures: {}): {}. {}",
                        client.name(), getConsecutiveFailures(client.name()), e.getMessage(),
                        i < total - 1 ? "Trying next provider..." : "No more providers to try.");
            }
        }

        // 所有模型都在冷却中 → 重置健康状态，强制重试一个
        if (skipped == total) {
            log.warn("All {} providers in cooldown, resetting health and retrying first available", total);
            resetAllHealth();
            NamedChatClient fallback = clients.get(Math.floorMod(startIdx, total));
            try {
                ChatResponse response = fallback.chatClient().prompt()
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
                recordSuccess(fallback.name());
                return new AiCallResult<>(entity, fallback.model(), extractTokens(response));
            } catch (Exception e) {
                lastException = e;
                recordFailure(fallback.name());
            }
        }

        throw new RuntimeException("All " + total + " providers failed", lastException);
    }

    /**
     * 原始文本调用：不做结构化输出解析，直接返回 LLM 的文本响应。
     * 同样具备 round-robin + fallback + 冷却跳过机制。
     */
    public RawCallResult callRaw(String systemPrompt, String userPrompt) throws Exception {
        int total = clients.size();
        int startIdx = Math.floorMod(counter.getAndIncrement(), total);
        Exception lastException = null;

        for (int i = 0; i < total; i++) {
            int idx = (startIdx + i) % total;
            NamedChatClient client = clients.get(idx);

            if (isInCooldown(client.name())) {
                continue;
            }

            try {
                ChatResponse response = client.chatClient().prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .chatResponse();

                String content = response.getResult().getOutput().getText();
                int tokensUsed = extractTokens(response);
                recordSuccess(client.name());
                return new RawCallResult(content, client.model(), tokensUsed);

            } catch (Exception e) {
                lastException = e;
                recordFailure(client.name());
                log.warn("Provider {} failed: {}", client.name(), e.getMessage());
            }
        }
        throw new RuntimeException("All " + total + " providers failed", lastException);
    }

    /** 原始调用结果封装 */
    public record RawCallResult(String content, String model, int tokensUsed) {}

    /** 查看下一个将被选中的模型（不递增计数器） */
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

    public Map<String, ProviderHealth> getHealthMap() {
        return Map.copyOf(healthMap);
    }

    // ==================== 模型健康状态追踪 ====================

    /** 判断模型是否在冷却期内（连续失败 >= 阈值 且 距上次失败不超过冷却时间） */
    private boolean isInCooldown(String providerName) {
        ProviderHealth health = healthMap.get(providerName);
        if (health == null) return false;
        if (health.consecutiveFailures < FAILURE_THRESHOLD) return false;
        return Instant.now().toEpochMilli() - health.lastFailureTime < COOLDOWN_MS;
    }

    /** 记录成功：重置连续失败计数 */
    private void recordSuccess(String providerName) {
        healthMap.put(providerName, new ProviderHealth(0, 0));
    }

    /** 记录失败：连续失败计数 +1 */
    private void recordFailure(String providerName) {
        ProviderHealth prev = healthMap.getOrDefault(providerName, new ProviderHealth(0, 0));
        healthMap.put(providerName, new ProviderHealth(
                prev.consecutiveFailures + 1, Instant.now().toEpochMilli()));
    }

    private int getConsecutiveFailures(String providerName) {
        ProviderHealth health = healthMap.get(providerName);
        return health != null ? health.consecutiveFailures : 0;
    }

    /** 重置所有模型的健康状态（所有模型都冷却时的兜底操作） */
    private void resetAllHealth() {
        healthMap.clear();
    }

    /** 模型健康状态记录 */
    public record ProviderHealth(int consecutiveFailures, long lastFailureTime) {}

    /** 从 ChatResponse 中提取 token 消耗量 */
    private int extractTokens(ChatResponse response) {
        try {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var usage = response.getMetadata().getUsage();
                Number total = usage.getTotalTokens();
                return total != null ? total.intValue() : 0;
            }
        } catch (Exception e) {
            log.debug("Failed to extract token usage: {}", e.getMessage());
        }
        return 0;
    }
}
