package com.majm.rag.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingestion_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false, unique = true)
    private UUID documentId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    // Initialize using the ready-batch query's DB clock, while allowing retry updates.
    @Generated(event = EventType.INSERT, writable = true)
    @ColumnTransformer(write = "coalesce(?, current_timestamp)")
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public IngestionOutbox(UUID documentId, UUID knowledgeBaseId) {
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public IngestionOutboxEvent toEvent() {
        return new IngestionOutboxEvent(id, documentId, knowledgeBaseId, attemptCount);
    }

    public void markPublished() {
        status = Status.PUBLISHED;
        publishedAt = Instant.now();
        lastError = null;
    }

    public void markRetry(int attemptCount, Instant nextAttemptAt, String error) {
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error;
    }

    public enum Status {
        PENDING,
        PUBLISHED
    }
}
