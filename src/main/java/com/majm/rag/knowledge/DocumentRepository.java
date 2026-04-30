package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    Page<Document> findByKnowledgeBaseId(UUID knowledgeBaseId, Pageable pageable);
}
