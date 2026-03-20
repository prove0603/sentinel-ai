package com.zhuangjie.sentinel.analyzer.provider;

/**
 * Abstraction for an LLM provider that can answer a system+user prompt pair.
 */
public interface ModelProvider {

    String name();

    String modelName();

    ModelResponse call(String systemPrompt, String userPrompt) throws Exception;

    record ModelResponse(String content, String model, int tokensUsed) {}
}
