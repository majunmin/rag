package com.majm.rag.knowledge;

import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.KnowledgeBaseResponse;
import com.majm.rag.knowledge.dto.UpdateKnowledgeBaseRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return ResponseEntity.ok(KnowledgeBaseResponse.from(service.create(request)));
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
                                        @RequestBody UpdateKnowledgeBaseRequest request) {
        return KnowledgeBaseResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
