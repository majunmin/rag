package com.majm.rag.retrieval;

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

    private RetrievalLimits() {}
}
