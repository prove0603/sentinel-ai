package com.zhuangjie.sentinel.analyzer.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * AI 调用日志 Advisor（Spring AI CallAdvisor）。
 * <p>
 * 附加在每个 ChatClient 上，记录每次 AI 调用的：
 * - provider 名称
 * - 调用耗时（毫秒）
 * - token 消耗（promptTokens / completionTokens / totalTokens）
 */
@Slf4j
public class AiLoggingAdvisor implements CallAdvisor {

    private final String providerName;

    public AiLoggingAdvisor(String providerName) {
        this.providerName = providerName;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        log.debug("[{}] AI call started", providerName);

        ChatClientResponse response = chain.nextCall(request);
        long elapsed = System.currentTimeMillis() - start;

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getMetadata() != null
                && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            log.info("[{}] AI call completed: {}ms, promptTokens={}, completionTokens={}, totalTokens={}",
                    providerName, elapsed,
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        } else {
            log.info("[{}] AI call completed: {}ms", providerName, elapsed);
        }

        return response;
    }

    @Override
    public String getName() {
        return "AiLoggingAdvisor-" + providerName;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
