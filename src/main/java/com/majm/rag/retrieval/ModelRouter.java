package com.majm.rag.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final Map<String, EmbeddingModel> embeddingModels;

    public EmbeddingModel getEmbeddingModel(String modelName) {
        return embeddingModels.values().stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No embedding model available for: " + modelName));
    }
}
