package com.majm.rag.retrieval.rewrite;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Output of the query-rewrite pipeline, handed to {@code RetrievalService}.
 *
 * <ul>
 *   <li>{@code originalQuery}  — what the user actually typed; always preserved
 *       so the rerank stage scores against real intent, not a HyDE pseudo-doc.</li>
 *   <li>{@code embeddingQuery} — the text fed into {@code vectorStore.similaritySearch}.
 *       For HyDE this is the LLM-generated hypothetical answer; for everything
 *       else it equals {@code originalQuery} (or the conversational rewrite).</li>
 *   <li>{@code expandedQueries} — extra phrasings for Multi-Query recall.
 *       Empty means single-recall path. Does NOT include {@code embeddingQuery}.</li>
 * </ul>
 *
 * Build via {@link #passthrough(String)} for the fail-soft / disabled case.
 */
public record RewriteResult(
    String originalQuery,
    String embeddingQuery,
    List<String> expandedQueries
) {

    public RewriteResult {
        if (StringUtils.isBlank(originalQuery)) {
            throw new IllegalArgumentException("originalQuery is required");
        }
        if (StringUtils.isBlank(embeddingQuery)) {
            embeddingQuery = originalQuery;
        }
        expandedQueries = expandedQueries == null ? List.of() : List.copyOf(expandedQueries);
    }

    /** Identity rewrite: same query everywhere, no expansion. */
    public static RewriteResult passthrough(String query) {
        return new RewriteResult(query, query, List.of());
    }

    public boolean hasExpansion() {
        return CollectionUtils.isNotEmpty(expandedQueries);
    }
}
