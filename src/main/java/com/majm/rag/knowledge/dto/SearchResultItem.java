package com.majm.rag.knowledge.dto;

import java.util.Map;

public record SearchResultItem(String content, Map<String, Object> metadata) {}
