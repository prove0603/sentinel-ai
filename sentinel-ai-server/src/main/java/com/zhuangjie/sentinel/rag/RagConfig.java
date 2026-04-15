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

/**
 * RAG 基础设施配置。
 * <p>
 * 条件加载：仅当 {@code sentinel.rag.enabled=true} 时生效。
 * 创建 PgVectorStore Bean，使用 PostgreSQL + pgvector 作为向量数据库，
 * 配合 DashScope text-embedding-v3（1024 维）进行知识条目的向量化存储和语义检索。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sentinel.rag.enabled", havingValue = "true")
public class RagConfig {

    /**
     * 创建 PgVectorStore：HNSW 索引 + 余弦距离 + 1024 维向量。
     * 向量表名为 knowledge_vector，启动时自动创建 schema。
     */
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
