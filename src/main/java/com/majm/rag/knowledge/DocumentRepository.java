package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByKnowledgeBaseId(UUID knowledgeBaseId, Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM Document d WHERE d.knowledgeBase.id = :kbId")
    void deleteByKnowledgeBaseId(@Param("kbId") UUID kbId);
}
