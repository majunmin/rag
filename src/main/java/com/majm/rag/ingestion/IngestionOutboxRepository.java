package com.majm.rag.ingestion;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IngestionOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public void enqueue(UUID documentId, UUID knowledgeBaseId) {
        jdbcTemplate.update("""
            INSERT INTO ingestion_outbox (document_id, knowledge_base_id)
            VALUES (?, ?)
            """, documentId, knowledgeBaseId);
    }

    public List<IngestionOutboxEvent> lockReadyBatch(int limit) {
        int boundedLimit = Math.clamp(limit, 1, 100);
        return jdbcTemplate.query("""
            SELECT id, document_id, knowledge_base_id, attempt_count
              FROM ingestion_outbox
             WHERE status = 'PENDING'
               AND next_attempt_at <= NOW()
             ORDER BY created_at
             FOR UPDATE SKIP LOCKED
             LIMIT ?
            """, (rs, rowNum) -> new IngestionOutboxEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class),
                rs.getInt("attempt_count")),
            boundedLimit);
    }

    public void markPublished(UUID id) {
        jdbcTemplate.update("""
            UPDATE ingestion_outbox
               SET status = 'PUBLISHED', published_at = NOW(), last_error = NULL, updated_at = NOW()
             WHERE id = ?
            """, id);
    }

    public void markRetry(UUID id, int attemptCount, Instant nextAttemptAt, String error) {
        jdbcTemplate.update("""
            UPDATE ingestion_outbox
               SET attempt_count = ?, next_attempt_at = ?, last_error = ?, updated_at = NOW()
             WHERE id = ?
            """, attemptCount, Timestamp.from(nextAttemptAt), error, id);
    }
}
