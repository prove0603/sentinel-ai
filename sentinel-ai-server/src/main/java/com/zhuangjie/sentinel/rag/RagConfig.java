package com.zhuangjie.sentinel.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "sentinel.rag.enabled", havingValue = "true")
public class RagConfig {

    @Bean
    public PgVectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        log.info("Creating PgVectorStore with HNSW index, cosine distance, dimensions=1024");
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("knowledge_vector")
                .build();
    }
}
