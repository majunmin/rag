package com.majm.rag.retrieval.api;

import com.majm.rag.retrieval.api.dto.SearchKnowledgeBaseRequest;
import com.majm.rag.retrieval.api.dto.SearchResultItem;
import com.majm.rag.retrieval.application.RetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Retrieval", description = "Search knowledge-base chunks")
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService retrievalService;

    @Operation(summary = "Test vector similarity search within a knowledge base")
    @PostMapping("/{id}/search")
    public List<SearchResultItem> search(@PathVariable UUID id,
                                         @Valid @RequestBody SearchKnowledgeBaseRequest request) {
        return retrievalService.search(id, request.query(), request.topK())
            .stream()
            .map(doc -> new SearchResultItem(doc.getText(), doc.getMetadata()))
            .toList();
    }
}
