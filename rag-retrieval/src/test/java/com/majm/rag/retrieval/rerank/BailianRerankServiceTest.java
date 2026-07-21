package com.majm.rag.retrieval.rerank;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class BailianRerankServiceTest {

    private WireMockServer wireMock;
    private BailianRerankService service;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        service = new BailianRerankService(wireMock.baseUrl(), "test-key", "gte-rerank-v2",
            Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void reordersByRelevanceScore() {
        // Input order: 0, 1, 2; rerank says 2 is most relevant, then 0, then 1.
        wireMock.stubFor(post(urlPathEqualTo("/services/rerank/text-rerank/text-rerank"))
            .withHeader("Authorization", equalTo("Bearer test-key"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "output": {
                        "results": [
                          {"index": 2, "relevance_score": 0.91},
                          {"index": 0, "relevance_score": 0.55},
                          {"index": 1, "relevance_score": 0.12}
                        ]
                      }
                    }
                    """)));

        List<Document> input = docs(3);
        List<Document> out = service.rerank("how to configure SSL", input, 3);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).getText()).isEqualTo("chunk-2");
        assertThat(out.get(1).getText()).isEqualTo("chunk-0");
        assertThat(out.get(2).getText()).isEqualTo("chunk-1");
        assertThat(out.get(0).getMetadata()).containsEntry("rerank_score", 0.91);
    }

    @Test
    void respectsTopKWhenSmallerThanCandidates() {
        wireMock.stubFor(post(urlPathEqualTo("/services/rerank/text-rerank/text-rerank"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "output": {
                        "results": [
                          {"index": 4, "relevance_score": 0.95},
                          {"index": 0, "relevance_score": 0.80},
                          {"index": 2, "relevance_score": 0.40},
                          {"index": 1, "relevance_score": 0.20},
                          {"index": 3, "relevance_score": 0.05}
                        ]
                      }
                    }
                    """)));

        List<Document> out = service.rerank("q", docs(5), 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getText()).isEqualTo("chunk-4");
        assertThat(out.get(1).getText()).isEqualTo("chunk-0");
    }

    @Test
    void fallsBackToRecallOrder_onApiError() {
        wireMock.stubFor(post(urlPathEqualTo("/services/rerank/text-rerank/text-rerank"))
            .willReturn(aResponse().withStatus(500).withBody("downstream error")));

        List<Document> input = docs(4);
        List<Document> out = service.rerank("q", input, 3);

        // Recall-only fallback: first topK in original order, no rerank_score added.
        assertThat(out).hasSize(3);
        assertThat(out.get(0).getText()).isEqualTo("chunk-0");
        assertThat(out.get(1).getText()).isEqualTo("chunk-1");
        assertThat(out.get(2).getText()).isEqualTo("chunk-2");
        assertThat(out.get(0).getMetadata()).doesNotContainKey("rerank_score");
    }

    @Test
    void fallsBackToRecallOrder_onTimeout() {
        wireMock.stubFor(post(urlPathEqualTo("/services/rerank/text-rerank/text-rerank"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(5000)   // service has 2s timeout
                .withBody("{}")));

        List<Document> out = service.rerank("q", docs(3), 3);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).getText()).isEqualTo("chunk-0");
    }

    @Test
    void fallsBackToRecallOrder_onMalformedResponse() {
        wireMock.stubFor(post(urlPathEqualTo("/services/rerank/text-rerank/text-rerank"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"output\": null}")));

        List<Document> out = service.rerank("q", docs(3), 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getText()).isEqualTo("chunk-0");
    }

    @Test
    void emptyCandidatesReturnsEmpty() {
        assertThat(service.rerank("q", List.of(), 5)).isEmpty();
    }

    @Test
    void singleCandidateSkipsApiCall() {
        // No stub registered; if the service called WireMock it'd 404 and we'd
        // fall back to the same single candidate, but the contract is to skip.
        List<Document> input = docs(1);
        List<Document> out = service.rerank("q", input, 5);

        assertThat(out).hasSize(1);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void ignoresOutOfRangeIndexFromApi() {
        // Defensive: API returns an index outside our input array. Should
        // be silently dropped, not crash.
        wireMock.stubFor(post(urlPathEqualTo("/services/rerank/text-rerank/text-rerank"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "output": {
                        "results": [
                          {"index": 99, "relevance_score": 0.99},
                          {"index": 0,  "relevance_score": 0.50}
                        ]
                      }
                    }
                    """)));

        List<Document> out = service.rerank("q", docs(2), 5);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getText()).isEqualTo("chunk-0");
    }

    private static List<Document> docs(int n) {
        return IntStream.range(0, n)
            .mapToObj(i -> new Document("chunk-" + i))
            .toList();
    }
}
