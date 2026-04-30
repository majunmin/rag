package com.majm.rag.ingestion.dto;

import java.util.UUID;

public record UploadDocumentResponse(UUID documentId, String name, String status) {}
