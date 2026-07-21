package com.majm.rag.ingestion.domain;

import java.util.UUID;

public record IngestionMessage(UUID documentId, UUID knowledgeBaseId) {}
