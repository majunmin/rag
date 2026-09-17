package com.majm.rag.ingestion.infrastructure.persistence;

import com.majm.rag.ingestion.domain.IngestionOutbox;
import com.majm.rag.ingestion.domain.IngestionOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IngestionOutboxRepository extends JpaRepository<IngestionOutbox, UUID> {

    @Transactional
    default void enqueue(UUID documentId, UUID knowledgeBaseId) {
        save(new IngestionOutbox(documentId, knowledgeBaseId));
    }

    // The caller must keep this transaction open until publishing/retry completes.
    @Transactional(propagation = Propagation.MANDATORY)
    default List<IngestionOutboxEvent> lockReadyBatch(int limit) {
        return findReadyForUpdate(Math.clamp(limit, 1, 100)).stream()
            .map(IngestionOutbox::toEvent)
            .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
        SELECT *
          FROM ingestion_outbox
         WHERE status = 'PENDING'
           AND next_attempt_at <= NOW()
         ORDER BY created_at, id
         LIMIT :limit
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<IngestionOutbox> findReadyForUpdate(@Param("limit") int limit);

    @Transactional
    default void markPublished(UUID id) {
        findById(id).ifPresent(IngestionOutbox::markPublished);
    }

    @Transactional
    default void markRetry(UUID id, int attemptCount, Instant nextAttemptAt, String error) {
        findById(id).ifPresent(event -> event.markRetry(attemptCount, nextAttemptAt, error));
    }
}
