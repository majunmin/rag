package com.majm.rag.knowledge.infrastructure.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface VectorChunkRepository extends Repository<VectorChunk, UUID> {

    @Transactional(readOnly = true)
    @Query(value = """
        SELECT id, content, metadata
          FROM vector_store
         WHERE metadata->>'document_id' = :documentId
         ORDER BY CAST(metadata->>'chunk_index' AS integer)
        """, nativeQuery = true)
    List<VectorChunk> findByDocumentId(@Param("documentId") String documentId);

    // Keep the persistence context intact: ingestion updates its managed Document
    // after removing obsolete chunks, so clearing here would discard those updates.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'document_id' = :documentId",
        nativeQuery = true)
    int deleteByDocumentId(@Param("documentId") String documentId);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = """
        DELETE FROM vector_store
         WHERE metadata->>'document_id' = :documentId
           AND CAST(metadata->>'chunk_index' AS integer) >= :fromIndex
        """, nativeQuery = true)
    int deleteByDocumentIdFromIndex(@Param("documentId") String documentId,
                                    @Param("fromIndex") int fromIndex);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'knowledge_base_id' = :knowledgeBaseId",
        nativeQuery = true)
    int deleteByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);
}
