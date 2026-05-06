package com.majm.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ChatRequest(
    @NotNull UUID knowledgeBaseId,
    @NotBlank String question,
    int topK
) {
    public static final int DEFAULT_TOP_K = 5;

    public ChatRequest {
        if (topK <= 0) {
            topK = DEFAULT_TOP_K;
        }
    }
}
