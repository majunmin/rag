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
@Getter @Setter
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

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
