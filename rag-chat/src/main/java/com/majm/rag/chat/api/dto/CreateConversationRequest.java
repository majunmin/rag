package com.majm.rag.chat.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateConversationRequest(@NotNull UUID knowledgeBaseId) {}
