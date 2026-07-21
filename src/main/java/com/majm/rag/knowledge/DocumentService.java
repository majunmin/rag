package com.majm.rag.knowledge;

import com.majm.rag.common.exception.ResourceNotFoundException;
import com.majm.rag.ingestion.StorageService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.dto.DocumentChunkResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
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
        if (kbId == null) {
            throw new IllegalArgumentException("kbId is required");
        }
        return documentRepository.findByKnowledgeBaseId(kbId, pageable)
            .map(d -> new DocumentListItem(
                d.getId(), d.getName(), d.getFileType(),
                d.getStatus().name(), d.getChunkCount(), d.getCreatedAt(), d.getErrorMessage()));
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

        if (StringUtils.isNotBlank(filePath) && !"URL".equalsIgnoreCase(doc.getFileType())) {
            storageService.delete(filePath);
        }
    }

    private Document loadAndCheckOwnership(UUID kbId, UUID docId) {
        if (kbId == null) {
            throw new IllegalArgumentException("kbId is required");
        }
        if (docId == null) {
            throw new IllegalArgumentException("docId is required");
        }

        Document doc = documentRepository.findById(docId)
            .orElseThrow(() -> ResourceNotFoundException.of("Document", docId));

        UUID ownerId = doc.getKnowledgeBase() == null ? null : doc.getKnowledgeBase().getId();
        if (!Objects.equals(kbId, ownerId)) {
            // Don't leak whether the doc exists under a different KB; treat
            // both "not found" and "wrong KB" as plain 404 to the client.
            throw ResourceNotFoundException.of("Document", docId);
        }
        return doc;
    }

    public record DocumentListItem(UUID id, String name, String fileType, String status,
                                    int chunkCount, LocalDateTime createdAt, String errorMessage) {}
}
