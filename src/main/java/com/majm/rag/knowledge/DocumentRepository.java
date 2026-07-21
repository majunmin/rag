package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.Document;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByKnowledgeBaseId(UUID knowledgeBaseId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Returns non-null on-disk file paths for all documents in the given KB.
     * Used by KnowledgeBaseService.delete to clean up files after the DB CASCADE
     * removes the rows.
     */
    @Query("SELECT d.filePath FROM Document d WHERE d.knowledgeBase.id = :kbId "
        + "AND d.filePath IS NOT NULL AND UPPER(d.fileType) <> 'URL'")
    List<String> findFilePathsByKnowledgeBaseId(@Param("kbId") UUID kbId);
}
