package com.majm.rag.knowledge.api.dto;

import java.util.Map;
import java.util.UUID;

public record DocumentChunkResponse(
    UUID id,
    int chunkIndex,
    String content,
    Map<String, Object> metadata
) {}
