package com.majm.rag.retrieval;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Two-stage retrieval:
 *
 * <ol>
 *   <li>Vector recall: pgvector cosine similarity returns up to
 *       {@code app.rerank.top-k-recall} candidates, KB-scoped via metadata
 *       filter.</li>
 *   <li>Rerank: a cross-encoder ({@link RerankService}) rescores the
 *       (query, chunk) pairs and returns the top-K. When the rerank service
 *       is disabled or fails, the {@link NoOpRerankService} / fail-soft
 *       fallback returns the recall order untouched.</li>
 * </ol>
 *
 * The caller-supplied {@code topK} is clamped to
 * {@link RetrievalLimits#MAX_TOP_K}; the recall budget is clamped to
 * {@link RetrievalLimits#MAX_TOP_K_RECALL} and is always >= the final topK.
 */
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final String KNOWLEDGE_BASE_ID_FILTER = "knowledge_base_id";

    private final VectorStore vectorStore;
    private final RerankService rerankService;

    @Value("${app.rerank.top-k-recall:20}")
    private int defaultTopKRecall;

    public List<Document> search(UUID knowledgeBaseId, String query, int topK) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("knowledgeBaseId is required");
        }
        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("query is required");
        }
        int finalTopK = clamp(topK, 1, RetrievalLimits.MAX_TOP_K);
        int recallSize = clamp(defaultTopKRecall, finalTopK, RetrievalLimits.MAX_TOP_K_RECALL);

        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        List<Document> candidates = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(recallSize)
                .filterExpression(filter.eq(KNOWLEDGE_BASE_ID_FILTER, knowledgeBaseId.toString()).build())
                .build()
        );

        if (CollectionUtils.isEmpty(candidates)) {
            return List.of();
        }
        return rerankService.rerank(query, candidates, finalTopK);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
