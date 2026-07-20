package com.majm.rag.ingestion;

import java.util.UUID;

public record IngestionOutboxEvent(
    UUID id,
    UUID documentId,
    UUID knowledgeBaseId,
    int attemptCount
) {}
