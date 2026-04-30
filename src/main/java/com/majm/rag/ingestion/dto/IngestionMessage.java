package com.majm.rag.ingestion.dto;

import java.util.UUID;

public record IngestionMessage(UUID documentId, UUID knowledgeBaseId) {}
