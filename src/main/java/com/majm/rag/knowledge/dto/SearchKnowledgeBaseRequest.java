package com.majm.rag.knowledge.dto;

import com.majm.rag.retrieval.RetrievalLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SearchKnowledgeBaseRequest(
    @NotBlank String query,
    @Min(1) @Max(RetrievalLimits.MAX_TOP_K) int topK
) {}
