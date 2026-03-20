package com.zhuangjie.sentinel.analyzer;

import com.zhuangjie.sentinel.analyzer.provider.ModelProvider;
import com.zhuangjie.sentinel.analyzer.provider.ModelProvider.ModelResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes AI calls across multiple model providers using round-robin.
 * On failure, automatically falls back to the next provider.
 */
@Slf4j
public class ModelRouter {

    private final List<ModelProvider> providers;
    private final AtomicInteger counter = new AtomicInteger(0);

    public ModelRouter(List<ModelProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("At least one ModelProvider is required");
        }
        this.providers = List.copyOf(providers);
        log.info("ModelRouter initialized with {} providers: {}", providers.size(),
                providers.stream().map(p -> p.name() + "(" + p.modelName() + ")").toList());
    }

    public ModelResponse call(String systemPrompt, String userPrompt) throws Exception {
        int total = providers.size();
        int startIdx = counter.getAndIncrement() % total;
        if (startIdx < 0) startIdx += total;

        Exception lastException = null;

        for (int i = 0; i < total; i++) {
            int idx = (startIdx + i) % total;
            ModelProvider provider = providers.get(idx);

            try {
                ModelResponse response = provider.call(systemPrompt, userPrompt);
                if (i > 0) {
                    log.info("Fallback succeeded: {} (after {} failed attempts)", provider.name(), i);
                }
                return response;
            } catch (Exception e) {
                lastException = e;
                log.warn("Provider {} failed: {}. {}", provider.name(), e.getMessage(),
                        i < total - 1 ? "Trying next provider..." : "No more providers to try.");
            }
        }

        throw new RuntimeException("All " + total + " providers failed", lastException);
    }

    /**
     * Returns the provider that WOULD be selected next (for testing/monitoring).
     */
    public ModelProvider peekNext() {
        int idx = counter.get() % providers.size();
        if (idx < 0) idx += providers.size();
        return providers.get(idx);
    }

    public List<ModelProvider> getProviders() {
        return providers;
    }

    public int getCallCount() {
        return counter.get();
    }
}
