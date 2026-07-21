package com.majm.rag.knowledge.api.dto;

import jakarta.validation.constraints.Positive;

public record UpdateKnowledgeBaseRequest(
    String name,
    String description,
    @Positive Integer chunkSize,
    @Positive Integer chunkOverlap
) {}
