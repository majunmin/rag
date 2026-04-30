package com.majm.rag.knowledge;

import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.KnowledgeBaseResponse;
import com.majm.rag.knowledge.dto.SearchKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.SearchResultItem;
import com.majm.rag.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.majm.rag.retrieval.RetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "Knowledge Base", description = "Manage knowledge bases and perform vector similarity search")
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;
    private final RetrievalService retrievalService;

    @Operation(summary = "Create a knowledge base")
    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBaseResponse response = KnowledgeBaseResponse.from(service.create(request));
        URI location = URI.create("/api/v1/knowledge-bases/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "List all knowledge bases (paginated)")
    @GetMapping
    public Page<KnowledgeBaseResponse> list(Pageable pageable) {
        return service.listAll(pageable).map(KnowledgeBaseResponse::from);
    }

    @Operation(summary = "Get knowledge base by id")
    @GetMapping("/{id}")
    public KnowledgeBaseResponse get(@PathVariable UUID id) {
        return KnowledgeBaseResponse.from(service.getById(id));
    }

    @Operation(summary = "Update knowledge base configuration")
    @PutMapping("/{id}")
    public KnowledgeBaseResponse update(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return KnowledgeBaseResponse.from(service.update(id, request));
    }

    @Operation(summary = "Delete knowledge base (cascades documents and chunks)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

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
