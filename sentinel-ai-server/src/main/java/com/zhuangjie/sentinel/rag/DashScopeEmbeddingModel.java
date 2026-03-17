package com.zhuangjie.sentinel.rag;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts DashScope SDK's TextEmbedding API to Spring AI's EmbeddingModel interface.
 * Uses text-embedding-v3 (1024 dimensions) by default.
 */
@Slf4j
public class DashScopeEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 1024;
    private static final int MAX_BATCH_SIZE = 10;

    private final TextEmbedding textEmbedding;
    private final String model;
    private final String apiKey;

    public DashScopeEmbeddingModel(String apiKey, String model) {
        this.textEmbedding = new TextEmbedding();
        this.model = model;
        this.apiKey = apiKey;
        log.info("DashScope EmbeddingModel initialized: model={}, dimensions={}", model, DIMENSIONS);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>();

        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            List<Embedding> batchResult = callDashScope(batch, i);
            embeddings.addAll(batchResult);
        }

        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        List<Embedding> result = callDashScope(List.of(text), 0);
        if (result.isEmpty()) {
            return new float[DIMENSIONS];
        }
        return result.get(0).getOutput();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private List<Embedding> callDashScope(List<String> texts, int indexOffset) {
        try {
            TextEmbeddingParam.TextEmbeddingParamBuilder<?, ?> builder = TextEmbeddingParam.builder()
                    .model(model)
                    .texts(texts);

            if (apiKey != null && !apiKey.isBlank()) {
                builder.apiKey(apiKey);
            }

            TextEmbeddingResult result = textEmbedding.call(builder.build());

            List<Embedding> embeddings = new ArrayList<>();
            if (result.getOutput() != null && result.getOutput().getEmbeddings() != null) {
                for (TextEmbeddingResultItem item : result.getOutput().getEmbeddings()) {
                    float[] vector = toFloatArray(item.getEmbedding());
                    embeddings.add(new Embedding(vector, indexOffset + item.getTextIndex()));
                }
            }

            log.debug("DashScope embedding: {} texts, tokens={}",
                    texts.size(),
                    result.getUsage() != null ? result.getUsage().getTotalTokens() : "unknown");

            return embeddings;
        } catch (Exception e) {
            log.error("DashScope embedding call failed: {}", e.getMessage());
            return List.of();
        }
    }

    private float[] toFloatArray(List<Double> doubles) {
        float[] floats = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            floats[i] = doubles.get(i).floatValue();
        }
        return floats;
    }
}
