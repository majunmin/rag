package com.majm.rag.retrieval.rerank;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aliyun Bailian (DashScope) cross-encoder reranker.
 *
 * <p>Calls {@code POST {baseUrl}/services/rerank/text-rerank/text-rerank}
 * with the native DashScope payload (NOT OpenAI-compatible). Returns each
 * input document's relevance score; we sort by score desc and take topK.
 *
 * <p>Fail-soft: any HTTP / parsing error logs at warn and falls back to the
 * first {@code topK} of the input candidates (recall-only). RAG should
 * degrade quality, never crash, when the rerank service is down.
 */
@Slf4j
public class BailianRerankService implements RerankService {

    private static final String RERANK_PATH = "/services/rerank/text-rerank/text-rerank";

    private final RestClient http;
    private final String model;

    public BailianRerankService(String baseUrl, String apiKey, String model, Duration timeout) {
        this.model = model;
        this.http = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .requestFactory(timeoutFactory(timeout))
            .build();
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (CollectionUtils.isEmpty(candidates) || topK <= 0) {
            return List.of();
        }
        if (candidates.size() == 1) {
            return List.copyOf(candidates);
        }

        List<String> docs = candidates.stream()
            .map(d -> StringUtils.defaultString(d.getText()))
            .toList();

        try {
            RerankResponse resp = http.post()
                .uri(RERANK_PATH)
                .body(new RerankRequest(model,
                    new RerankInput(query, docs),
                    new RerankParameters(topK, false)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("rerank api status " + res.getStatusCode()
                        + ": " + new String(res.getBody().readAllBytes()));
                })
                .body(RerankResponse.class);

            List<RerankResult> results = (resp == null || resp.output() == null)
                ? null : resp.output().results();
            if (CollectionUtils.isEmpty(results)) {
                log.warn("rerank returned no results; falling back to recall order");
                return fallback(candidates, topK);
            }

            List<RerankResult> sorted = new ArrayList<>(results);
            sorted.sort(Comparator.comparingDouble(RerankResult::relevance_score).reversed());

            List<Document> reordered = new ArrayList<>(Math.min(topK, sorted.size()));
            for (int i = 0; i < sorted.size() && reordered.size() < topK; i++) {
                int idx = sorted.get(i).index();
                if (idx >= 0 && idx < candidates.size()) {
                    Document src = candidates.get(idx);
                    Document withScore = src.mutate()
                        .metadata(scoredMetadata(src.getMetadata(), sorted.get(i).relevance_score()))
                        .build();
                    reordered.add(withScore);
                }
            }
            return List.copyOf(reordered);

        } catch (Exception e) {
            log.warn("rerank failed ({}); falling back to recall order", e.getMessage());
            return fallback(candidates, topK);
        }
    }

    private static List<Document> fallback(List<Document> candidates, int topK) {
        return candidates.size() <= topK
            ? List.copyOf(candidates)
            : List.copyOf(candidates.subList(0, topK));
    }

    private static Map<String, Object> scoredMetadata(Map<String, Object> base, double score) {
        Map<String, Object> copy = new HashMap<>(MapUtils.emptyIfNull(base));
        copy.put("rerank_score", score);
        return copy;
    }

    private static ClientHttpRequestFactory timeoutFactory(Duration timeout) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) timeout.toMillis());
        f.setReadTimeout((int) timeout.toMillis());
        return f;
    }

    // ---------- DashScope JSON shape ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RerankRequest(String model, RerankInput input, RerankParameters parameters) {}

    record RerankInput(String query, List<String> documents) {}

    record RerankParameters(Integer top_n, Boolean return_documents) {}

    record RerankResponse(RerankOutput output) {}

    record RerankOutput(List<RerankResult> results) {}

    record RerankResult(int index, double relevance_score) {}
}
