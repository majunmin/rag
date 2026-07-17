package com.majm.rag.ingestion;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}
