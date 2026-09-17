package com.majm.rag.knowledge.application;

import com.majm.rag.knowledge.api.dto.DocumentChunkResponse;
import com.majm.rag.knowledge.infrastructure.persistence.VectorChunkRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChunkQueryService {

    private final VectorChunkRepository repository;

    @Transactional(readOnly = true)
    public List<DocumentChunkResponse> listByDocument(UUID documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }
        return repository.findByDocumentId(documentId.toString()).stream()
            .map(chunk -> {
                Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
                return new DocumentChunkResponse(
                    chunk.getId(), parseChunkIndex(metadata), chunk.getContent(), metadata);
            })
            .toList();
    }

    @Transactional
    public int deleteByDocument(UUID documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }
        return repository.deleteByDocumentId(documentId.toString());
    }

    @Transactional
    public int deleteByDocumentFromIndex(UUID documentId, int fromIndex) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }
        return repository.deleteByDocumentIdFromIndex(documentId.toString(), Math.max(0, fromIndex));
    }

    @Transactional
    public int deleteByKnowledgeBase(UUID knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("knowledgeBaseId is required");
        }
        return repository.deleteByKnowledgeBaseId(knowledgeBaseId.toString());
    }

    private int parseChunkIndex(Map<String, Object> metadata) {
        Object raw = MapUtils.getObject(metadata, "chunk_index");
        if (raw instanceof Number n) {
            return n.intValue();
        }
        // toInt handles null and non-numeric strings, returning 0 by default.
        return NumberUtils.toInt(raw == null ? null : raw.toString(), 0);
    }
}
