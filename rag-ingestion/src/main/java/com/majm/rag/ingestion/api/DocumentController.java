package com.majm.rag.ingestion.api;

import com.majm.rag.ingestion.application.DocumentUploadService;
import com.majm.rag.ingestion.application.WebDocumentService;
import com.majm.rag.ingestion.api.dto.CreateWebDocumentRequest;
import com.majm.rag.ingestion.api.dto.UploadDocumentResponse;
import com.majm.rag.knowledge.api.dto.DocumentChunkResponse;
import com.majm.rag.knowledge.application.DocumentService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "Document", description = "Upload, list, delete documents and inspect chunks within a knowledge base")
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final WebDocumentService webDocumentService;
    private final DocumentService documentService;

    @Operation(summary = "Upload a document (async ingestion via Kafka). Returns 202 Accepted.")
    @PostMapping
    public ResponseEntity<UploadDocumentResponse> upload(@PathVariable UUID kbId,
                                                          @RequestParam("file") MultipartFile file) {
        return ResponseEntity.accepted().body(uploadService.upload(kbId, file));
    }

    @Operation(summary = "Crawl a web page and ingest its readable content asynchronously")
    @PostMapping("/url")
    public ResponseEntity<UploadDocumentResponse> submitUrl(
        @PathVariable UUID kbId, @Valid @RequestBody CreateWebDocumentRequest request) {
        return ResponseEntity.accepted().body(webDocumentService.submit(kbId, request.url()));
    }

    @Operation(summary = "List documents in a knowledge base (paginated)")
    @GetMapping
    public Page<DocumentService.DocumentListItem> list(@PathVariable UUID kbId, Pageable pageable) {
        return documentService.list(kbId, pageable);
    }

    @Operation(summary = "Delete a document and its chunks")
    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(@PathVariable UUID kbId, @PathVariable UUID docId) {
        documentService.delete(kbId, docId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Inspect chunks of a document (ordered by chunk index)")
    @GetMapping("/{docId}/chunks")
    public List<DocumentChunkResponse> chunks(@PathVariable UUID kbId, @PathVariable UUID docId) {
        return documentService.chunks(kbId, docId);
    }
}
