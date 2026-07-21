package com.majm.rag.ingestion.api.dto;

import java.util.UUID;

public record UploadDocumentResponse(UUID documentId, String name, String status) {}
