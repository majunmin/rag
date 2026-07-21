package com.majm.rag.knowledge.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_base")
@Getter
@Setter
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    /**
     * Informational only: records which embedding model was (or will be) used to embed
     * this knowledge base. The runtime VectorStore is configured globally via
     * application.yml (spring.ai.openai.embedding.options.model), so changing this
     * field does NOT route ingestion or retrieval to a different model. If a future
     * release introduces per-KB model routing, callers should re-embed existing
     * chunks before changing this value.
     */
    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel = "text-embedding-v3";

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize = 512;

    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap = 64;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KnowledgeBaseStatus status = KnowledgeBaseStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
