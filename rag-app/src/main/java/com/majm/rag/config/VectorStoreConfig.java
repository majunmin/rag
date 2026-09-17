package com.majm.rag.config;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}")
    private int dimensions;

    /**
     * Aliyun DashScope (Bailian) embeddings cap each request at 10 inputs.
     * Default 10 keeps Bailian working out of the box; raise via
     * {@code APP_EMBEDDING_BATCH_SIZE} for providers with higher limits
     * (OpenAI text-embedding-3 allows ~2048).
     */
    @Value("${app.embedding.batch-size:10}")
    private int embeddingBatchSize;

    @Bean
    public BatchingStrategy embeddingBatchingStrategy() {
        return new FixedSizeBatchingStrategy(embeddingBatchSize);
    }

    @Bean
    @Primary
    // PgVectorStore's adapter requires JdbcTemplate; business persistence uses JPA.
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate,
                                   @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                                   BatchingStrategy embeddingBatchingStrategy) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .indexType(HNSW)
            .distanceType(COSINE_DISTANCE)
            .dimensions(dimensions)
            .initializeSchema(false)
            .batchingStrategy(embeddingBatchingStrategy)
            .build();
    }
}
