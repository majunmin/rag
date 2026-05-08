package com.majm.rag.ingestion;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test for the ingestion pipeline. Uses the local docker-compose
 * stack (postgres on :5432, kafka on :9092). Skips automatically if either
 * port isn't open. Embeddings are stubbed via WireMock so the test never
 * depends on a live LLM provider.
 *
 * To run: `docker compose up -d` then `mvn test -Dtest=IngestionConsumerIntegrationTest`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("IngestionConsumer end-to-end (local PG + Kafka + WireMock)")
class IngestionConsumerIntegrationTest {

    static WireMockServer wireMock;

    @BeforeAll
    static void requireLocalInfra() {
        assumeTrue(canConnect("localhost", 5432),
            "Postgres not reachable on localhost:5432 — run docker compose up -d");
        assumeTrue(canConnect("localhost", 9092),
            "Kafka not reachable on localhost:9092 — run docker compose up -d");
    }

    @DynamicPropertySource
    static void overrideOpenAi(DynamicPropertyRegistry registry) {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        registry.add("spring.ai.openai.base-url", wireMock::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.ai.openai.embedding.options.model", () -> "text-embedding-v3");
        // Make Spring AI retry failures only once with a tiny backoff so
        // failure-path tests don't sit in retry loops for a minute+.
        registry.add("spring.ai.retry.max-attempts", () -> "1");
        registry.add("spring.ai.retry.backoff.initial-interval", () -> "10ms");
        registry.add("spring.ai.retry.backoff.max-interval", () -> "20ms");
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    @Autowired KnowledgeBaseService kbService;
    @Autowired DocumentRepository documentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @LocalServerPort int port;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void clean() {
        wireMock.resetAll();
        // Test isolation: wipe the rows this test created. Other rows from
        // dev/manual runs are left alone (DB cascade handles documents+chunks).
        jdbcTemplate.update(
            "DELETE FROM vector_store WHERE metadata->>'document_id' IN "
                + "(SELECT id::text FROM document WHERE name LIKE 'integration-test-%')");
        jdbcTemplate.update("DELETE FROM document WHERE name LIKE 'integration-test-%'");
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE name LIKE 'integration-test-%'");
    }

    @Test
    @DisplayName("happy path: PENDING -> PROCESSING -> DONE with chunks in vector_store")
    void uploadFlow_endsInDone() {
        stubEmbeddingsOk();

        KnowledgeBase kb = kbService.create(new CreateKnowledgeBaseRequest(
            "integration-test-happy", "happy path", "text-embedding-v3", 256, 32));

        UUID docId = uploadFile(kb.getId(), "integration-test-doc.txt",
            ("Spring Boot is a framework for building production-ready applications. "
                + "It provides starters, auto-configuration, and embedded servers. "
                + "RAG combines retrieval with generation to answer questions over a corpus.")
                .getBytes());

        await().atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                Document doc = documentRepository.findById(docId).orElseThrow();
                assertThat(doc.getStatus()).isEqualTo(DocumentStatus.DONE);
                assertThat(doc.getChunkCount()).isGreaterThan(0);
                assertThat(doc.getErrorMessage()).isNull();
            });

        Integer vectorRowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
            Integer.class, docId.toString());
        Document persisted = documentRepository.findById(docId).orElseThrow();
        assertThat(vectorRowCount).isEqualTo(persisted.getChunkCount());
    }

    @Test
    @DisplayName("failure path: embeddings 500 -> FAILED + errorMessage persisted")
    void uploadFlow_endsInFailed_whenEmbeddingsErrors() {
        wireMock.stubFor(post(urlPathMatching("/.*embeddings.*"))
            .willReturn(aResponse().withStatus(500).withBody("downstream error")));

        KnowledgeBase kb = kbService.create(new CreateKnowledgeBaseRequest(
            "integration-test-fail", "failure path", "text-embedding-v3", 256, 32));

        UUID docId = uploadFile(kb.getId(), "integration-test-fail.txt",
            "Whatever content; the embeddings stub will 500.".getBytes());

        // Each Kafka retry resets PROCESSING then writes FAILED, so the
        // observable status flickers until DLT publish stops the loop. Wait
        // long enough for KafkaConfig's DefaultErrorHandler to exhaust its
        // 3-attempt backoff (1s + 2s + 4s = 7s nominal, plus pull/poll jitter).
        await().atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                Document doc = documentRepository.findById(docId).orElseThrow();
                assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
                assertThat(doc.getErrorMessage()).isNotBlank();
            });

        Integer vectorRowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
            Integer.class, docId.toString());
        assertThat(vectorRowCount).isZero();
    }

    private static boolean canConnect(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void stubEmbeddingsOk() {
        wireMock.stubFor(post(urlPathMatching("/.*embeddings.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildEmbeddingResponse(20))));
    }

    /** OpenAI-compatible embeddings response with N entries × 1024-dim vectors. */
    private String buildEmbeddingResponse(int count) {
        Random rng = new Random(42);
        StringBuilder data = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) data.append(",");
            data.append("{\"object\":\"embedding\",\"index\":").append(i)
                .append(",\"embedding\":").append(randomVector(rng, 1024)).append("}");
        }
        data.append("]");
        return "{"
            + "\"object\":\"list\","
            + "\"data\":" + data
            + ",\"model\":\"text-embedding-v3\","
            + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}"
            + "}";
    }

    private String randomVector(Random rng, int dim) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", rng.nextGaussian() * 0.1));
        }
        sb.append("]");
        return sb.toString();
    }

    private UUID uploadFile(UUID kbId, String filename, byte[] content) {
        String url = "http://localhost:" + port + "/api/v1/knowledge-bases/" + kbId + "/documents";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(content) {
            @Override public String getFilename() { return filename; }
        });

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = http.exchange(url, HttpMethod.POST, request, Map.class);
        return UUID.fromString(response.getBody().get("documentId").toString());
    }
}
