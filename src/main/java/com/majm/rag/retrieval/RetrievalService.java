package com.majm.rag.retrieval;

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

import java.util.List;
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

        // Let rerank return a wider pool so MMR has choices to make.
        int rerankKeep = clamp(finalTopK * Math.max(1, rerankExpandFactor),
            finalTopK, Math.min(recallSize, RetrievalLimits.MAX_TOP_K_RECALL));
        List<Document> reranked = rerankService.rerank(query, candidates, rerankKeep);

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
