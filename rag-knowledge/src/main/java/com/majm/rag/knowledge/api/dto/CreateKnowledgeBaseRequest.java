package com.majm.rag.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateKnowledgeBaseRequest(
    @NotBlank String name,
    String description,
    @NotBlank String embeddingModel,
    @Positive int chunkSize,
    @Positive int chunkOverlap
) {}
