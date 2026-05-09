package com.majm.rag.retrieval;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Reorders vector-recall candidates by their semantic relevance to a query
 * using a cross-encoder. The contract is fail-soft: implementations MUST NOT
 * throw on rerank failure; they should log the issue and return the input
 * candidates unchanged (recall-only fallback).
 *
 * <p>Inputs and outputs use Spring AI {@link Document} so callers don't need
 * to convert.
 */
public interface RerankService {

    /**
     * @param query     user query, never null/blank
     * @param candidates ordered candidates from vector recall (typically up
     *                   to {@link RetrievalLimits#MAX_TOP_K_RECALL})
     * @param topK      how many to return after rerank; clamped to candidates.size()
     * @return reordered candidates, sized at most {@code topK}. On failure,
     *         returns the first {@code topK} of the input unchanged.
     */
    List<Document> rerank(String query, List<Document> candidates, int topK);
}
