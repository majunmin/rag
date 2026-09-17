package com.majm.rag.persistence;

import com.majm.rag.ingestion.domain.IngestionOutbox;
import com.majm.rag.ingestion.domain.IngestionOutboxEvent;
import com.majm.rag.ingestion.infrastructure.persistence.IngestionOutboxRepository;
import com.majm.rag.knowledge.api.dto.DocumentChunkResponse;
import com.majm.rag.knowledge.application.ChunkQueryService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.infrastructure.persistence.DocumentRepository;
import com.majm.rag.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.majm.rag.knowledge.infrastructure.persistence.VectorChunk;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real PostgreSQL tests in an isolated schema; no Kafka or model provider required.
 */
@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = JpaPersistenceIntegrationTest.Config.class)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaPersistenceIntegrationTest {

    private static final String URL = System.getProperty("test.database.url", "jdbc:postgresql://localhost:5432/rag");
    private static final String USER = System.getProperty("test.database.username", "rag");
    private static final String PASSWORD = System.getProperty("test.database.password", "rag");
    private static final String SCHEMA = "jpa_test_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean databaseAvailable;

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {Document.class, VectorChunk.class, IngestionOutbox.class})
    @EnableJpaRepositories(basePackageClasses = {DocumentRepository.class, IngestionOutboxRepository.class})
    @Import(ChunkQueryService.class)
    static class Config {
    }

    @BeforeAll
    static void requirePostgres() {
        URI uri = URI.create(URL.substring("jdbc:".length()));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort()), 500);
            databaseAvailable = true;
        } catch (Exception ignored) {
            databaseAvailable = false;
        }
        assumeTrue(databaseAvailable, "PostgreSQL unavailable; configure -Dtest.database.url to run JPA tests");
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
            + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropTestSchema() throws Exception {
        if (databaseAvailable) {
            try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }

    @Autowired
    EntityManager entityManager;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    KnowledgeBaseRepository knowledgeBases;
    @Autowired
    DocumentRepository documents;
    @Autowired
    IngestionOutboxRepository outbox;
    @Autowired
    ChunkQueryService chunks;

    @AfterEach
    void clean() {
        // All connections use this class's isolated schema. Owner deletion cascades.
        knowledgeBases.deleteAllInBatch();
    }

    @Test
    void chunksRoundTripJsonAndSortIndicesNumerically() {
        Document document = createDocument();
        Document other = createDocument();
        insertChunk(document, "10");
        insertChunk(document, 2);
        insertChunk(document, 0);
        insertChunk(other, 1);

        List<DocumentChunkResponse> result = chunks.listByDocument(document.getId());
        assertThat(result).extracting(DocumentChunkResponse::chunkIndex).containsExactly(0, 2, 10);
        assertThat(result.get(1).metadata()).containsEntry("chunk_index", 2)
            .containsEntry("document_id", document.getId().toString());
        assertThat(result.get(2).metadata()).containsEntry("chunk_index", "10");
        assertThat(result).allSatisfy(chunk -> {
            assertThat(chunk.id()).isNotNull();
            assertThat(chunk.content()).isEqualTo("chunk content");
        });
        assertThat(chunks.listByDocument(UUID.randomUUID())).isEmpty();
    }

    @Test
    void chunkDeletesRespectOwnerAndLeaveDocumentManaged() {
        Document document = createDocument();
        Document other = createDocument();
        insertChunk(document, 0);
        insertChunk(document, 2);
        insertChunk(other, 0);

        inTransaction(() -> {
            Document managed = documents.findById(document.getId()).orElseThrow();
            assertThat(chunks.deleteByDocumentFromIndex(document.getId(), 1)).isEqualTo(1);
            assertThat(entityManager.contains(managed)).isTrue();
            managed.setStatus(DocumentStatus.DONE);
            managed.setChunkCount(1);
            return null;
        });
        assertThat(documents.findById(document.getId()).orElseThrow().getStatus()).isEqualTo(DocumentStatus.DONE);
        assertThat(chunks.listByDocument(document.getId())).hasSize(1);
        assertThat(chunks.deleteByDocumentFromIndex(document.getId(), -5)).isEqualTo(1);
        assertThat(chunks.deleteByDocument(document.getId())).isZero();
        assertThat(chunks.listByDocument(other.getId())).hasSize(1);
        assertThat(chunks.deleteByDocument(other.getId())).isEqualTo(1);

        insertChunk(document, 0);
        insertChunk(other, 0);
        assertThat(chunks.deleteByKnowledgeBase(document.getKnowledgeBase().getId())).isEqualTo(1);
        assertThat(chunks.listByDocument(other.getId())).hasSize(1);
    }

    @Test
    void outboxPersistsRetriesAndPublicationThroughDirtyChecking() {
        Document document = createDocument();
        IngestionOutboxEvent event = inTransaction(() -> {
            outbox.enqueue(document.getId(), document.getKnowledgeBase().getId());
            // A new event is ready even in the insertion transaction: both
            // next_attempt_at and the readiness predicate use the database clock.
            var batch = outbox.lockReadyBatch(0);
            assertThat(batch).hasSize(1);
            return batch.getFirst();
        });
        Instant retryAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS);

        outbox.markRetry(event.id(), 1, retryAt, "Kafka unavailable");
        IngestionOutbox retry = outbox.findById(event.id()).orElseThrow();
        assertThat(retry.getStatus()).isEqualTo(IngestionOutbox.Status.PENDING);
        assertThat(retry.getAttemptCount()).isEqualTo(1);
        assertThat(retry.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(retry.getLastError()).isEqualTo("Kafka unavailable");
        assertThat(retry.getCreatedAt()).isNotNull();
        assertThat(retry.getUpdatedAt()).isNotNull();
        assertThat(inTransaction(() -> outbox.lockReadyBatch(20))).isEmpty();

        outbox.markRetry(event.id(), 2, Instant.now().minusSeconds(1), "retry");
        inTransaction(() -> {
            assertThat(outbox.lockReadyBatch(20)).extracting(IngestionOutboxEvent::attemptCount).containsExactly(2);
            outbox.markPublished(event.id());
            return null;
        });
        IngestionOutbox published = outbox.findById(event.id()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(IngestionOutbox.Status.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getLastError()).isNull();
        assertThat(inTransaction(() -> outbox.lockReadyBatch(20))).isEmpty();
    }

    @Test
    void documentAndOutboxRollbackTogether() {
        assertThatThrownBy(() -> inTransaction(() -> {
            Document document = createDocument();
            outbox.enqueue(document.getId(), document.getKnowledgeBase().getId());
            outbox.flush();
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(documents.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void competingPublishersSkipLockedRowsUntilTransactionEnds() throws Exception {
        Document first = createDocument();
        Document second = createDocument();
        outbox.enqueue(first.getId(), first.getKnowledgeBase().getId());
        outbox.enqueue(second.getId(), second.getKnowledgeBase().getId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstBatch = executor.submit(() -> inTransaction(() -> {
                var batch = outbox.lockReadyBatch(1);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release outbox lock");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return batch;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var secondBatch = inTransaction(() -> {
                entityManager.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
                return outbox.lockReadyBatch(20);
            });
            assertThat(secondBatch).hasSize(1);
            release.countDown();
            assertThat(firstBatch.get(10, TimeUnit.SECONDS)).hasSize(1)
                .doesNotContainAnyElementsOf(secondBatch);
            assertThat(inTransaction(() -> outbox.lockReadyBatch(20))).hasSize(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lockingRequiresAnEnclosingTransaction() {
        assertThatThrownBy(() -> outbox.lockReadyBatch(20))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void chunkDeletesRollbackWithTheEnclosingTransaction() {
        Document document = createDocument();
        insertChunk(document, 0);
        assertThatThrownBy(() -> inTransaction(() -> {
            chunks.deleteByDocument(document.getId());
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(chunks.listByDocument(document.getId())).hasSize(1);
    }

    private Document createDocument() {
        return inTransaction(() -> {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setName("jpa-test");
            knowledgeBases.saveAndFlush(kb);
            Document document = new Document();
            document.setKnowledgeBase(kb);
            document.setName("jpa-test.txt");
            document.setFileType("TXT");
            return documents.saveAndFlush(document);
        });
    }

    private void insertChunk(Document document, Object index) {
        String jsonIndex = index instanceof String ? "\"" + index + "\"" : index.toString();
        inTransaction(() -> entityManager.createNativeQuery("""
                INSERT INTO vector_store (id, content, metadata)
                VALUES (:id, 'chunk content', CAST(:metadata AS jsonb))
                """)
            .setParameter("id", UUID.randomUUID())
            .setParameter("metadata", """
                {"document_id":"%s","knowledge_base_id":"%s","chunk_index":%s}
                """.formatted(document.getId(), document.getKnowledgeBase().getId(), jsonIndex))
            .executeUpdate());
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
