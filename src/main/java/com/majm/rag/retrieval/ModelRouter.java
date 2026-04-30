package com.majm.rag.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Routes embedding model requests by name.
 * Reserved for future multi-model routing when KnowledgeBase.embeddingModel field is used
 * to select a model per knowledge base. Currently the single registered EmbeddingModel
 * is used project-wide via VectorStoreConfig.
 */
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final Map<String, EmbeddingModel> embeddingModels;

    public EmbeddingModel getEmbeddingModel(String modelName) {
        EmbeddingModel model = embeddingModels.get(modelName);
        if (model == null) {
            throw new IllegalArgumentException(
                "No embedding model registered with name: " + modelName
                + ". Available: " + embeddingModels.keySet());
        }
        return model;
    }
}
