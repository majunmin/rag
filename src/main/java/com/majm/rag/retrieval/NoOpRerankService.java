package com.majm.rag.retrieval;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Pass-through reranker used when {@code app.rerank.enabled=false}. Returns
 * the first {@code topK} candidates from the vector recall in their original
 * cosine-distance order.
 */
public class NoOpRerankService implements RerankService {

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (CollectionUtils.isEmpty(candidates) || topK <= 0) {
            return List.of();
        }
        return candidates.size() <= topK
            ? List.copyOf(candidates)
            : List.copyOf(candidates.subList(0, topK));
    }
}
