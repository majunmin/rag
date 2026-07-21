package com.majm.rag.knowledge.api;

import com.majm.rag.knowledge.api.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.api.dto.KnowledgeBaseResponse;
import com.majm.rag.knowledge.api.dto.UpdateKnowledgeBaseRequest;
import com.majm.rag.knowledge.application.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Knowledge Base", description = "Manage knowledge bases")
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

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

}
