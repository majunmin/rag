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

    private static final String KNOWLEDGE_BASE_ID_FILTER = "knowledge_base_id";

    private final VectorStore vectorStore;

    public List<Document> search(UUID knowledgeBaseId, String query, int topK) {
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filter.eq(KNOWLEDGE_BASE_ID_FILTER, knowledgeBaseId.toString()).build())
                .build()
        );
    }
}
