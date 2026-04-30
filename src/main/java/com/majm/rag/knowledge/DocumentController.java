package com.majm.rag.knowledge;

import com.majm.rag.ingestion.DocumentUploadService;
import com.majm.rag.ingestion.dto.UploadDocumentResponse;
import com.majm.rag.knowledge.domain.DocumentChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    @PostMapping
    public ResponseEntity<UploadDocumentResponse> upload(@PathVariable UUID kbId,
                                                          @RequestParam("file") MultipartFile file) {
        return ResponseEntity.accepted().body(uploadService.upload(kbId, file));
    }

    @GetMapping
    public Page<DocumentListItem> list(@PathVariable UUID kbId, Pageable pageable) {
        return documentRepository.findByKnowledgeBaseId(kbId, pageable)
            .map(d -> new DocumentListItem(d.getId(), d.getName(), d.getFileType(),
                d.getStatus().name(), d.getChunkCount(), d.getCreatedAt()));
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(@PathVariable UUID kbId, @PathVariable UUID docId) {
        chunkRepository.deleteByDocumentId(docId);
        documentRepository.deleteById(docId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{docId}/chunks")
    public List<DocumentChunk> chunks(@PathVariable UUID kbId, @PathVariable UUID docId) {
        return chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
    }

    record DocumentListItem(UUID id, String name, String fileType, String status,
                             int chunkCount, java.time.LocalDateTime createdAt) {}
}
