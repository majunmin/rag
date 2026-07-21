package com.majm.rag.retrieval.api.dto;

import java.util.Map;

public record SearchResultItem(String content, Map<String, Object> metadata) {}
