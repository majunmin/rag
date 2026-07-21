package com.majm.rag.retrieval.application;

/**
 * Hard limits applied across retrieval-related DTOs and services.
 * Used by ChatRequest, ConversationMessageRequest, SearchKnowledgeBaseRequest,
 * and RetrievalService as a defense-in-depth clamp.
 */
public final class RetrievalLimits {


    /** Default top-K when caller omits or supplies a non-positive value. */
    public static final int DEFAULT_TOP_K = 5;

    /** Upper bound on top-K to prevent runaway pgvector scans and prompt-token blowup. */
    public static final int MAX_TOP_K = 50;

    /**
     * Upper bound on top-K-recall (the number of candidates fetched from the
     * vector store before rerank). DashScope gte-rerank-v2 accepts up to 500
     * documents per call but past ~50 the rerank latency dominates. Tighten
     * here if needed.
     */
    public static final int MAX_TOP_K_RECALL = 100;

    private RetrievalLimits() {}
}
