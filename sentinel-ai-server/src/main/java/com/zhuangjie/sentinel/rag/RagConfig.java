package com.zhuangjie.sentinel.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;

/**
 * RAG infrastructure: DashScope Embedding + PGVector store.
 * Reuses the application's primary PostgreSQL DataSource.
 * Activated by sentinel.rag.enabled=true.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sentinel.rag.enabled", havingValue = "true")
public class RagConfig {

    @Bean
    public EmbeddingModel dashScopeEmbeddingModel(
            @Value("${sentinel.ai.api-key:${AI_DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${sentinel.rag.embedding-model:text-embedding-v3}") String embeddingModel) {
        return new DashScopeEmbeddingModel(apiKey, embeddingModel);
    }

    @Bean
    public PgVectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashScopeEmbeddingModel) {
        log.info("Creating PgVectorStore with HNSW index, cosine distance, dimensions=1024");
        return PgVectorStore.builder(jdbcTemplate, dashScopeEmbeddingModel)
                .dimensions(1024)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("knowledge_vector")
                .build();
    }
}
