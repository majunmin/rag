package com.majm.rag.retrieval;

import com.majm.rag.retrieval.rewrite.RewriteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Three-stage retrieval:
 *
 * <ol>
 *   <li><b>Vector recall</b>: pgvector cosine similarity returns up to
 *       {@code app.rerank.top-k-recall} candidates, KB-scoped via metadata
 *       filter.</li>
 *   <li><b>Rerank</b>: a cross-encoder ({@link RerankService}) rescores the
 *       (query, chunk) pairs. We keep {@code finalTopK * rerank-expand-factor}
 *       reranked candidates — enough headroom for MMR to drop duplicates.
 *       On rerank failure the fail-soft {@link NoOpRerankService} / fallback
 *       returns the recall order untouched.</li>
 *   <li><b>Threshold + MMR</b>: drop candidates below
 *       {@code app.rerank.score-threshold} (when non-negative), then run
 *       {@link MmrDeduplicator} to take the final top-K with duplicate
 *       suppression.</li>
 * </ol>
 *
 * When the threshold filters all candidates an empty list is returned so the
 * LLM prompt can take the "I don't know" path instead of hallucinating from
 * unrelated context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final String KNOWLEDGE_BASE_ID_FILTER = "knowledge_base_id";

    private final VectorStore vectorStore;
    private final RerankService rerankService;

    @Value("${app.rerank.top-k-recall:20}")
    private int defaultTopKRecall;

    /** How many more reranked candidates to feed into MMR than the final topK. */
    @Value("${app.rerank.expand-factor:2}")
    private int rerankExpandFactor;

    /** Drop candidates with rerank_score below this; negative = disabled. */
    @Value("${app.rerank.score-threshold:-1.0}")
    private double scoreThreshold;

    /** MMR balance; 1.0 = pure relevance, 0.0 = pure diversity. */
    @Value("${app.rerank.mmr-lambda:0.7}")
    private double mmrLambda;

    /** Disable MMR entirely if you only want threshold filtering. */
    @Value("${app.rerank.mmr-enabled:true}")
    private boolean mmrEnabled;

    /** RRF constant used to fuse Multi-Query recall results. Larger = flatter weighting of high ranks. */
    @Value("${app.query-rewrite.rrf-k:60}")
    private int rrfK;

    /** Direct search path — no rewrite, no expansion. Used by the KB search endpoint. */
    public List<Document> search(UUID knowledgeBaseId, String query, int topK) {
        // Validate up front so the legacy arity keeps emitting "query is required"
        // for blanks instead of leaking RewriteResult's internal constraint message.
        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("query is required");
        }
        return search(knowledgeBaseId, query, topK, RewriteResult.passthrough(query));
    }

    /**
     * Search with a pre-computed {@link RewriteResult}. Used by ChatService.
     *
     * <p>{@code rewrite.embeddingQuery()} drives vector recall.
     * {@code rewrite.originalQuery()} drives rerank (so the cross-encoder sees
     * the real intent, not a HyDE pseudo-doc).
     * {@code rewrite.expandedQueries()}, when non-empty, triggers Multi-Query
     * recall + RRF fusion before rerank.
     */
    public List<Document> search(UUID knowledgeBaseId, String query, int topK, RewriteResult rewrite) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("knowledgeBaseId is required");
        }
        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("query is required");
        }
        if (rewrite == null) {
            rewrite = RewriteResult.passthrough(query);
        }

        int finalTopK = clamp(topK, 1, RetrievalLimits.MAX_TOP_K);
        int recallSize = clamp(defaultTopKRecall, finalTopK, RetrievalLimits.MAX_TOP_K_RECALL);

        List<Document> candidates = rewrite.hasExpansion()
            ? multiQueryRecall(knowledgeBaseId, rewrite, recallSize)
            : singleRecall(knowledgeBaseId, rewrite.embeddingQuery(), recallSize);

        if (CollectionUtils.isEmpty(candidates)) {
            return List.of();
        }

        // Let rerank return a wider pool so MMR has choices to make.
        int rerankKeep = clamp(finalTopK * Math.max(1, rerankExpandFactor),
            finalTopK, Math.min(recallSize, RetrievalLimits.MAX_TOP_K_RECALL));
        List<Document> reranked = rerankService.rerank(rewrite.originalQuery(), candidates, rerankKeep);

        List<Document> afterThreshold = applyThreshold(reranked);
        if (afterThreshold.isEmpty()) {
            log.info("retrieval: all candidates dropped by score-threshold={} (highest was below); "
                + "returning empty context", scoreThreshold);
            return List.of();
        }

        if (!mmrEnabled || afterThreshold.size() <= finalTopK) {
            return afterThreshold.size() <= finalTopK
                ? afterThreshold
                : List.copyOf(afterThreshold.subList(0, finalTopK));
        }
        return MmrDeduplicator.apply(afterThreshold, finalTopK, mmrLambda);
    }

    private List<Document> singleRecall(UUID knowledgeBaseId, String embeddingQuery, int recallSize) {
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(embeddingQuery)
                .topK(recallSize)
                .filterExpression(filter.eq(KNOWLEDGE_BASE_ID_FILTER, knowledgeBaseId.toString()).build())
                .build()
        );
    }

    /**
     * Run one recall per query (embedding + each expansion) and fuse the results
     * with Reciprocal Rank Fusion:
     *
     * <pre>
     *   fused(d) = sum_{q in queries} 1 / (k + rank_q(d))
     * </pre>
     *
     * <p>Each query gets the same {@code recallSize} budget. The fused candidate
     * list is capped at {@code recallSize} too — rerank should see roughly the
     * same volume as a single-query search, just diversified across phrasings.
     */
    private List<Document> multiQueryRecall(UUID knowledgeBaseId, RewriteResult rewrite, int recallSize) {
        List<String> queries = new ArrayList<>();
        queries.add(rewrite.embeddingQuery());
        queries.addAll(rewrite.expandedQueries());

        // Use Document.getId() when available, else hash on text for de-dup across recalls.
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Document> byKey = new LinkedHashMap<>();
        int hits = 0;

        for (String q : queries) {
            if (StringUtils.isBlank(q)) continue;
            List<Document> recall = singleRecall(knowledgeBaseId, q, recallSize);
            if (CollectionUtils.isEmpty(recall)) continue;
            hits++;
            for (int rank = 0; rank < recall.size(); rank++) {
                Document d = recall.get(rank);
                String key = dedupKey(d);
                byKey.putIfAbsent(key, d);
                // RRF: rank is 0-based; the standard formula uses 1-based, so we add 1.
                scores.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
            }
        }
        if (scores.isEmpty()) {
            log.debug("multi-query recall: all {} queries returned 0 docs", queries.size());
            return List.of();
        }
        log.debug("multi-query recall: {} queries hit, {} unique docs fused", hits, scores.size());

        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(recallSize)
            .map(e -> byKey.get(e.getKey()))
            .toList();
    }

    private static String dedupKey(Document d) {
        if (d == null) return "null";
        // Prefer explicit id; fall back to hashing the text (good enough — same chunk
        // will produce identical text across recall calls).
        if (StringUtils.isNotBlank(d.getId())) return d.getId();
        String text = StringUtils.defaultString(d.getText());
        return "h:" + text.hashCode() + ":" + text.length();
    }

    private List<Document> applyThreshold(List<Document> ranked) {
        if (scoreThreshold < 0 || CollectionUtils.isEmpty(ranked)) {
            return ranked == null ? List.of() : ranked;
        }
        return ranked.stream()
            .filter(d -> {
                Object raw = d.getMetadata() == null ? null : d.getMetadata().get("rerank_score");
                // Missing score = keep (NoOpRerankService / fail-soft fallback path).
                if (!(raw instanceof Number n)) return true;
                return n.doubleValue() >= scoreThreshold;
            })
            .toList();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
