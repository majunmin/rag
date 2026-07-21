package com.majm.rag.retrieval.rerank;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maximal Marginal Relevance (MMR) post-processor for reranked chunks.
 *
 * <p>Vector recall + cross-encoder rerank often surface near-duplicate chunks
 * (the same passage split into overlapping chunks during ingestion shows up
 * multiple times at the top). MMR iteratively picks chunks that are both
 * highly relevant (rerank score) and sufficiently distinct from already-
 * selected chunks.
 *
 * <p>Similarity between candidates is computed as character-trigram Jaccard
 * similarity — cheap, language-agnostic, and adequate for catching
 * near-duplicate chunks produced by ingestion overlap. We deliberately do
 * NOT re-embed chunks here to avoid additional API round-trips.
 *
 * <pre>
 *   score(c) = lambda * relevance(c) - (1 - lambda) * max sim(c, s) for s in selected
 * </pre>
 *
 * <p>{@code lambda=1} reduces to pure relevance ordering (no de-dup);
 * {@code lambda=0} maximises diversity regardless of relevance.
 */
public final class MmrDeduplicator {

    private static final int SHINGLE_SIZE = 3;

    private MmrDeduplicator() {}

    /**
     * @param ranked     chunks already ordered by rerank relevance desc.
     *                   Each Document's metadata may carry {@code rerank_score};
     *                   if missing, rank position is used as a proxy.
     * @param topK       number of chunks to keep
     * @param lambda     balance in [0, 1]; 0.7 is a reasonable default
     * @return up to {@code topK} chunks, MMR-ordered
     */
    public static List<Document> apply(List<Document> ranked, int topK, double lambda) {
        if (CollectionUtils.isEmpty(ranked) || topK <= 0) {
            return List.of();
        }
        if (ranked.size() <= topK) {
            return List.copyOf(ranked);
        }

        // Clamp lambda defensively; misconfig shouldn't blow up retrieval.
        double lam = Math.max(0.0, Math.min(1.0, lambda));

        int n = ranked.size();
        double[] relevance = new double[n];
        List<Set<String>> shingles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            relevance[i] = relevanceScore(ranked.get(i), i, n);
            shingles.add(shingle(ranked.get(i).getText()));
        }

        List<Document> selected = new ArrayList<>(topK);
        boolean[] chosen = new boolean[n];

        while (selected.size() < topK) {
            int best = -1;
            double bestMmr = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (chosen[i]) continue;
                double maxSim = 0.0;
                for (int j = 0; j < n; j++) {
                    if (!chosen[j]) continue;
                    double s = jaccard(shingles.get(i), shingles.get(j));
                    if (s > maxSim) maxSim = s;
                }
                double mmr = lam * relevance[i] - (1.0 - lam) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    best = i;
                }
            }
            if (best < 0) break;
            chosen[best] = true;
            selected.add(ranked.get(best));
        }
        return List.copyOf(selected);
    }

    /**
     * Normalised relevance in [0, 1]. Prefer {@code rerank_score} from metadata;
     * fall back to position-based linear decay so callers without an explicit
     * score still get a monotonically decreasing signal.
     */
    private static double relevanceScore(Document doc, int idx, int total) {
        Object raw = doc.getMetadata() == null ? null : doc.getMetadata().get("rerank_score");
        if (raw instanceof Number n) {
            double v = n.doubleValue();
            return Math.max(0.0, Math.min(1.0, v));
        }
        return 1.0 - ((double) idx / Math.max(1, total));
    }

    /** Character-trigram set. Empty/blank text yields an empty set. */
    private static Set<String> shingle(String text) {
        if (text == null || text.length() < SHINGLE_SIZE) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        String normalized = text.toLowerCase();
        for (int i = 0; i <= normalized.length() - SHINGLE_SIZE; i++) {
            set.add(normalized.substring(i, i + SHINGLE_SIZE));
        }
        return set;
    }

    /** Jaccard similarity; 0.0 when either set is empty. */
    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> smaller = a.size() < b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int intersect = 0;
        for (String s : smaller) {
            if (larger.contains(s)) intersect++;
        }
        int union = a.size() + b.size() - intersect;
        return union == 0 ? 0.0 : (double) intersect / union;
    }

    // Kept for possible future use; not on the hot path.
    @SuppressWarnings("unused")
    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.asList(text.toLowerCase().split("\\s+"));
    }
}
