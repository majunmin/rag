package com.majm.rag.knowledge.infrastructure.persistence;

import com.majm.rag.knowledge.domain.KnowledgeBase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {
    Page<KnowledgeBase> findAll(Pageable pageable);
}
