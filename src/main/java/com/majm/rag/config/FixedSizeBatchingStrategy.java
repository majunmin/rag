package com.majm.rag.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document list into fixed-size sub-batches.
 *
 * <p>The default {@link org.springframework.ai.embedding.TokenCountBatchingStrategy}
 * batches by total input tokens and can produce a single batch with dozens of
 * documents. Aliyun DashScope (Bailian) embeddings reject any request with
 * more than 10 inputs (HTTP 400, "batch size is invalid, it should not be
 * larger than 10"), so we cap the batch size by document count instead.
 *
 * <p>Aliyun's published limit is 10. Other providers can override via
 * {@code app.embedding.batch-size}.
 */
public class FixedSizeBatchingStrategy implements BatchingStrategy {

    private final int maxBatchSize;

    public FixedSizeBatchingStrategy(int maxBatchSize) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be >= 1, got " + maxBatchSize);
        }
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<List<Document>> batches = new ArrayList<>(
            (documents.size() + maxBatchSize - 1) / maxBatchSize);
        for (int i = 0; i < documents.size(); i += maxBatchSize) {
            int end = Math.min(i + maxBatchSize, documents.size());
            batches.add(new ArrayList<>(documents.subList(i, end)));
        }
        return batches;
    }
}
