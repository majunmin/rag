package com.majm.rag.ingestion.domain;

import java.util.UUID;

public record IngestionOutboxEvent(
    UUID id,
    UUID documentId,
    UUID knowledgeBaseId,
    int attemptCount
) {}
