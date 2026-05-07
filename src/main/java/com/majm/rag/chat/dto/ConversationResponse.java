package com.majm.rag.chat.dto;

import com.majm.rag.chat.domain.Conversation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ConversationResponse(
    UUID id,
    UUID knowledgeBaseId,
    List<Map<String, String>> messages,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
            conversation.getId(),
            conversation.getKnowledgeBaseId(),
            List.copyOf(conversation.getMessages()),
            conversation.getCreatedAt(),
            conversation.getUpdatedAt()
        );
    }
}
