package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.UpdateKnowledgeBaseRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    @Transactional
    public KnowledgeBase create(CreateKnowledgeBaseRequest request) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setDescription(request.description());
        kb.setEmbeddingModel(request.embeddingModel());
        kb.setChunkSize(request.chunkSize());
        kb.setChunkOverlap(request.chunkOverlap());
        return repository.save(kb);
    }

    @Transactional(readOnly = true)
    public Page<KnowledgeBase> listAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public KnowledgeBase getById(UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("KnowledgeBase not found: " + id));
    }

    @Transactional
    public KnowledgeBase update(UUID id, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase kb = getById(id);
        if (request.name() != null) kb.setName(request.name());
        if (request.description() != null) kb.setDescription(request.description());
        if (request.chunkSize() != null) kb.setChunkSize(request.chunkSize());
        if (request.chunkOverlap() != null) kb.setChunkOverlap(request.chunkOverlap());
        return repository.save(kb);
    }

    @Transactional
    public void delete(UUID id) {
        getById(id);
        chunkRepository.deleteByKnowledgeBaseId(id);
        documentRepository.findByKnowledgeBaseId(id, Pageable.unpaged())
            .forEach(doc -> documentRepository.delete(doc));
        repository.deleteById(id);
    }
}
