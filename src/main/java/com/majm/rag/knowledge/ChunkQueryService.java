package com.majm.rag.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.majm.rag.knowledge.dto.DocumentChunkResponse;
import lombok.RequiredArgsConstructor;
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
        return jdbcTemplate.query(LIST_BY_DOCUMENT_SQL, (rs, rowNum) -> {
            UUID id = UUID.fromString(rs.getString("id"));
            String content = rs.getString("content");
            Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
            int chunkIndex = parseChunkIndex(metadata);
            return new DocumentChunkResponse(id, chunkIndex, content, metadata);
        }, documentId.toString());
    }

    public int deleteByDocument(UUID documentId) {
        return jdbcTemplate.update(
            "DELETE FROM vector_store WHERE metadata->>'document_id' = ?",
            documentId.toString());
    }

    public int deleteByKnowledgeBase(UUID knowledgeBaseId) {
        return jdbcTemplate.update(
            "DELETE FROM vector_store WHERE metadata->>'knowledge_base_id' = ?",
            knowledgeBaseId.toString());
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted vector_store metadata JSON", e);
        }
    }

    private int parseChunkIndex(Map<String, Object> metadata) {
        Object raw = metadata.get("chunk_index");
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
