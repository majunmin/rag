package com.majm.rag.retrieval.rewrite;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates the configured chain of {@link QueryRewriter}s with a single
 * overall timeout budget. Always fail-soft: a timeout, an LLM error, or any
 * misconfiguration degrades to {@link RewriteResult#passthrough(String)} so
 * chat still works on the user's literal query.
 *
 * <p>Configuration ({@code app.query-rewrite.*}):
 * <ul>
 *   <li>{@code enabled}    — master switch.</li>
 *   <li>{@code strategies} — comma-separated names, applied in order. The
 *       first strategy that produces a non-passthrough result is the new
 *       baseline; the next one operates on it. This lets you compose
 *       {@code conversational,hyde} → "first resolve coreference, then
 *       HyDE-embed the standalone query".</li>
 *   <li>{@code timeout-ms} — wall-clock budget for the whole chain.</li>
 * </ul>
 */
@Slf4j
@Service
public class QueryRewriteService {

    private final Map<String, QueryRewriter> available;
    private final boolean enabled;
    private final long timeoutMs;
    private final List<String> configuredNames;

    /** Resolved at startup; null if {@code enabled=false} or no valid strategies. */
    private List<QueryRewriter> activeChain;

    public QueryRewriteService(
        List<QueryRewriter> rewriters,
        @Value("${app.query-rewrite.enabled:true}") boolean enabled,
        @Value("${app.query-rewrite.strategies:conversational,hyde}") String strategies,
        @Value("${app.query-rewrite.timeout-ms:3000}") long timeoutMs
    ) {
        this.enabled = enabled;
        this.timeoutMs = Math.max(500L, timeoutMs);
        Map<String, QueryRewriter> byName = new HashMap<>();
        for (QueryRewriter r : CollectionUtils.emptyIfNull(rewriters)) {
            byName.put(r.name(), r);
        }
        this.available = Map.copyOf(byName);
        this.configuredNames = parseNames(strategies);
    }

    @PostConstruct
    void resolveChain() {
        if (!enabled) {
            log.info("QueryRewriteService: disabled (passthrough mode)");
            this.activeChain = List.of();
            return;
        }
        List<QueryRewriter> chain = new ArrayList<>();
        for (String name : configuredNames) {
            QueryRewriter r = available.get(name);
            if (r == null) {
                log.warn("QueryRewriteService: unknown strategy '{}', skipping; "
                    + "available={}", name, available.keySet());
                continue;
            }
            chain.add(r);
        }
        if (chain.isEmpty()) {
            log.warn("QueryRewriteService: no valid strategies in '{}'; falling back to passthrough",
                configuredNames);
        } else {
            log.info("QueryRewriteService: active chain={}, timeout={}ms",
                chain.stream().map(QueryRewriter::name).toList(), timeoutMs);
        }
        this.activeChain = List.copyOf(chain);
    }

    /**
     * Returns the rewrite result; never null, never throws. Caller can safely
     * use whatever comes back (worst case: identity passthrough).
     */
    public RewriteResult rewrite(String query, List<Map<String, String>> history) {
        if (StringUtils.isBlank(query)) {
            // RetrievalService validates this too, but defend in depth.
            throw new IllegalArgumentException("query is required");
        }
        if (CollectionUtils.isEmpty(activeChain)) {
            return RewriteResult.passthrough(query);
        }
        List<Map<String, String>> safeHistory = history == null ? List.of() : history;
        try {
            return CompletableFuture
                .supplyAsync(() -> runChain(query, safeHistory))
                .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("query-rewrite exceeded {}ms budget; using original query", timeoutMs);
            return RewriteResult.passthrough(query);
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("query-rewrite chain failed ({}); using original query",
                e.getMessage());
            return RewriteResult.passthrough(query);
        }
    }

    private RewriteResult runChain(String originalQuery, List<Map<String, String>> history) {
        RewriteResult current = RewriteResult.passthrough(originalQuery);
        for (QueryRewriter r : activeChain) {
            // Each step sees the current best standalone query (the originalQuery
            // after coreference resolution, for example). HyDE / Multi-Query then
            // augment the embeddingQuery and expandedQueries without losing the
            // upstream gains.
            RewriteResult step = r.rewrite(current.originalQuery(), history);
            current = merge(current, step);
        }
        return current;
    }

    /**
     * Combine the previous chain output with a new step's output.
     *
     * <p>Each strategy is invoked with the <i>current</i> standalone query
     * (post-coreference, if conversational ran). So {@code step.originalQuery()}
     * is either unchanged (passthrough — no information) or the new standalone
     * (e.g. ConversationalRewriter). We trust {@code step} to express which:
     * <ul>
     *   <li>If {@code step.embeddingQuery == step.originalQuery} and no expansion,
     *       step did nothing — keep {@code current}.</li>
     *   <li>If {@code step.embeddingQuery != step.originalQuery} (HyDE), take its
     *       embedding text; keep current's original (HyDE doesn't replace intent).</li>
     *   <li>If {@code step.hasExpansion()} (Multi-Query), take its expansions; keep
     *       current's embedding text.</li>
     *   <li>Otherwise (Conversational): step rewrote the standalone — take its
     *       originalQuery as the new baseline for both fields.</li>
     * </ul>
     */
    private static RewriteResult merge(RewriteResult current, RewriteResult step) {
        boolean hydeLike = !StringUtils.equals(step.embeddingQuery(), step.originalQuery());
        boolean expanding = step.hasExpansion();

        if (!hydeLike && !expanding && StringUtils.equals(step.originalQuery(), current.originalQuery())) {
            // Step was a passthrough; nothing to merge.
            return current;
        }
        if (hydeLike) {
            return new RewriteResult(
                current.originalQuery(),
                step.embeddingQuery(),
                current.expandedQueries());
        }
        if (expanding) {
            return new RewriteResult(
                current.originalQuery(),
                current.embeddingQuery(),
                step.expandedQueries());
        }
        // Conversational-style: standalone rewrite — reset both fields.
        return new RewriteResult(step.originalQuery(), step.originalQuery(), current.expandedQueries());
    }

    private static List<String> parseNames(String csv) {
        if (StringUtils.isBlank(csv)) return List.of();
        return Arrays.stream(csv.split(","))
            .map(StringUtils::trimToNull)
            .filter(java.util.Objects::nonNull)
            .map(StringUtils::lowerCase)
            .toList();
    }

    // ---- Test hooks ----
    Map<String, QueryRewriter> availableForTest() {
        return MapUtils.unmodifiableMap(available);
    }

    List<QueryRewriter> activeChainForTest() {
        return activeChain == null ? List.of() : List.copyOf(activeChain);
    }
}
