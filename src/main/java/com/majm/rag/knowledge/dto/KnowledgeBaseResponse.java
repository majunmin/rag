package com.majm.rag.knowledge.dto;

import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.domain.KnowledgeBaseStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record KnowledgeBaseResponse(
    UUID id,
    String name,
    String description,
    String embeddingModel,
    int chunkSize,
    int chunkOverlap,
    KnowledgeBaseStatus status,
    LocalDateTime createdAt
) {
    public static KnowledgeBaseResponse from(KnowledgeBase kb) {
        return new KnowledgeBaseResponse(
            kb.getId(), kb.getName(), kb.getDescription(),
            kb.getEmbeddingModel(), kb.getChunkSize(), kb.getChunkOverlap(),
            kb.getStatus(), kb.getCreatedAt()
        );
    }
}
