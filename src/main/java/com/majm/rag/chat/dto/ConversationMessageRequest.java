package com.majm.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record ConversationMessageRequest(@NotBlank String question, int topK) {
    public ConversationMessageRequest {
        if (topK <= 0) topK = 5;
    }
}
