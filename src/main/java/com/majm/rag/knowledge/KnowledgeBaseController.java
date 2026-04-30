package com.majm.rag.knowledge;

import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.KnowledgeBaseResponse;
import com.majm.rag.knowledge.dto.SearchKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.SearchResultItem;
import com.majm.rag.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.majm.rag.retrieval.RetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;
    private final RetrievalService retrievalService;

    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBaseResponse response = KnowledgeBaseResponse.from(service.create(request));
        URI location = URI.create("/api/v1/knowledge-bases/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public Page<KnowledgeBaseResponse> list(Pageable pageable) {
        return service.listAll(pageable).map(KnowledgeBaseResponse::from);
    }

    @GetMapping("/{id}")
    public KnowledgeBaseResponse get(@PathVariable UUID id) {
        return KnowledgeBaseResponse.from(service.getById(id));
    }

    @PutMapping("/{id}")
    public KnowledgeBaseResponse update(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return KnowledgeBaseResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/search")
    public List<SearchResultItem> search(@PathVariable UUID id,
                                         @Valid @RequestBody SearchKnowledgeBaseRequest request) {
        return retrievalService.search(id, request.query(), request.topK())
            .stream()
            .map(doc -> new SearchResultItem(doc.getText(), doc.getMetadata()))
            .toList();
    }
}
