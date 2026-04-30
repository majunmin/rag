package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    @Transactional
    void deleteByDocumentId(UUID documentId);

    @Transactional
    void deleteByKnowledgeBaseId(UUID knowledgeBaseId);
}
