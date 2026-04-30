package com.majm.rag.knowledge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SearchKnowledgeBaseRequest(
    @NotBlank String query,
    @Min(1) int topK
) {}
