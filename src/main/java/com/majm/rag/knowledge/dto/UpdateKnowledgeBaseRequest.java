package com.majm.rag.knowledge.dto;

public record UpdateKnowledgeBaseRequest(
    String name,
    String description,
    Integer chunkSize,
    Integer chunkOverlap
) {}
