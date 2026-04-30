package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);
    void deleteByDocumentId(UUID documentId);
    void deleteByKnowledgeBaseId(UUID knowledgeBaseId);
}
