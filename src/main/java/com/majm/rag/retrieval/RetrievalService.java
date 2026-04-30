package com.majm.rag.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final VectorStore vectorStore;

    public List<Document> search(UUID knowledgeBaseId, String query, int topK) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(b.eq("knowledge_base_id", knowledgeBaseId.toString()).build())
                .build()
        );
    }
}
