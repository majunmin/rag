package com.majm.rag.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.majm.rag.knowledge.dto.DocumentChunkResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChunkQueryService {

    private static final String LIST_BY_DOCUMENT_SQL = """
        SELECT id, content, metadata
          FROM vector_store
         WHERE metadata->>'document_id' = ?
         ORDER BY (metadata->>'chunk_index')::int
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<DocumentChunkResponse> listByDocument(UUID documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }
        return jdbcTemplate.query(LIST_BY_DOCUMENT_SQL, (rs, rowNum) -> {
            UUID id = UUID.fromString(rs.getString("id"));
            String content = rs.getString("content");
            Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
            int chunkIndex = parseChunkIndex(metadata);
            return new DocumentChunkResponse(id, chunkIndex, content, metadata);
        }, documentId.toString());
    }

    public int deleteByDocument(UUID documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }
        return jdbcTemplate.update(
            "DELETE FROM vector_store WHERE metadata->>'document_id' = ?",
            documentId.toString());
    }

    public int deleteByKnowledgeBase(UUID knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("knowledgeBaseId is required");
        }
        return jdbcTemplate.update(
            "DELETE FROM vector_store WHERE metadata->>'knowledge_base_id' = ?",
            knowledgeBaseId.toString());
    }

    private Map<String, Object> parseMetadata(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted vector_store metadata JSON", e);
        }
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
