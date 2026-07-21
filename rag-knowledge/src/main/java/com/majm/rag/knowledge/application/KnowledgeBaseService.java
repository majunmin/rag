package com.majm.rag.knowledge.application;

import com.majm.rag.common.exception.ResourceNotFoundException;
import com.majm.rag.knowledge.application.port.StorageService;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.api.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.api.dto.UpdateKnowledgeBaseRequest;
import com.majm.rag.knowledge.infrastructure.persistence.DocumentRepository;
import com.majm.rag.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final DocumentRepository documentRepository;
    private final ChunkQueryService chunkQueryService;
    private final StorageService storageService;

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
            .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeBase", id));
    }

    @Transactional
    public KnowledgeBase update(UUID id, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase kb = getById(id);
        if (request.name() != null) {
            kb.setName(request.name());
        }
        if (request.description() != null) {
            kb.setDescription(request.description());
        }
        if (request.chunkSize() != null) {
            kb.setChunkSize(request.chunkSize());
        }
        if (request.chunkOverlap() != null) {
            kb.setChunkOverlap(request.chunkOverlap());
        }
        return kb;
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw ResourceNotFoundException.of("KnowledgeBase", id);
        }
        List<String> filePaths = documentRepository.findFilePathsByKnowledgeBaseId(id);
        chunkQueryService.deleteByKnowledgeBase(id);
        repository.deleteById(id);
        for (String path : filePaths) {
            storageService.delete(path);
        }
    }
}
