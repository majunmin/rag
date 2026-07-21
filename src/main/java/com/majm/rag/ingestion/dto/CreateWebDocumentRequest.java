package com.majm.rag.ingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWebDocumentRequest(
    @NotBlank(message = "url is required")
    @Size(max = 1024, message = "url must not exceed 1024 characters")
    String url
) {}
