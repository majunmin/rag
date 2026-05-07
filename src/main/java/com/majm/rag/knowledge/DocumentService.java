package com.majm.rag.knowledge;

import com.majm.rag.common.exception.ResourceNotFoundException;
import com.majm.rag.ingestion.StorageService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.dto.DocumentChunkResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final ChunkQueryService chunkQueryService;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public Page<DocumentListItem> list(UUID kbId, Pageable pageable) {
        return documentRepository.findByKnowledgeBaseId(kbId, pageable)
            .map(d -> new DocumentListItem(
                d.getId(), d.getName(), d.getFileType(),
                d.getStatus().name(), d.getChunkCount(), d.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public List<DocumentChunkResponse> chunks(UUID kbId, UUID docId) {
        Document doc = loadAndCheckOwnership(kbId, docId);
        return chunkQueryService.listByDocument(doc.getId());
    }

    @Transactional
    public void delete(UUID kbId, UUID docId) {
        Document doc = loadAndCheckOwnership(kbId, docId);
        String filePath = doc.getFilePath();

        chunkQueryService.deleteByDocument(docId);
        documentRepository.delete(doc);

        if (filePath != null && !filePath.isBlank()) {
            storageService.delete(filePath);
        }
    }

    private Document loadAndCheckOwnership(UUID kbId, UUID docId) {
        Document doc = documentRepository.findById(docId)
            .orElseThrow(() -> ResourceNotFoundException.of("Document", docId));
        if (doc.getKnowledgeBase() == null || !kbId.equals(doc.getKnowledgeBase().getId())) {
            throw ResourceNotFoundException.of("Document", docId);
        }
        return doc;
    }

    public record DocumentListItem(UUID id, String name, String fileType, String status,
                                    int chunkCount, LocalDateTime createdAt) {}
}
