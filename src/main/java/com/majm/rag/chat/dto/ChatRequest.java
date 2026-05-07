package com.majm.rag.chat.dto;

import com.majm.rag.retrieval.RetrievalLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ChatRequest(
    @NotNull UUID knowledgeBaseId,
    @NotBlank String question,
    @Min(0) @Max(RetrievalLimits.MAX_TOP_K) int topK
) {
    public ChatRequest {
        if (topK <= 0) {
            topK = RetrievalLimits.DEFAULT_TOP_K;
        }
    }
}
