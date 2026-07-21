package com.majm.rag.retrieval.rewrite;

import java.util.List;
import java.util.Map;

/**
 * Pluggable strategy that produces an enriched view of the user's query.
 *
 * <p>Implementations MUST be fail-soft: any LLM / network problem should be
 * caught and surfaced as a passthrough {@link RewriteResult}, not propagated.
 * The orchestrator owns the timeout budget.
 */
public interface QueryRewriter {

    /**
     * @param query    user-supplied question (already non-blank)
     * @param history  prior conversation turns, most-recent last; never null.
     *                 Empty for single-turn requests. Entries are
     *                 {@code {role, content}} maps mirroring
     *                 {@code ChatService}'s in-memory shape.
     */
    RewriteResult rewrite(String query, List<Map<String, String>> history);

    /** Lower-cased identifier used in config and logs. */
    String name();
}
