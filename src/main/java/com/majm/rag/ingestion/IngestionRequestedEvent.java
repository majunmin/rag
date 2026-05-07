package com.majm.rag.ingestion;

import java.util.UUID;

public record IngestionRequestedEvent(UUID documentId, UUID knowledgeBaseId) {}
