package com.majm.rag.ingestion.application;

import com.majm.rag.ingestion.api.dto.UploadDocumentResponse;
import com.majm.rag.ingestion.infrastructure.persistence.IngestionOutboxRepository;
import com.majm.rag.knowledge.application.port.StorageService;
import com.majm.rag.knowledge.infrastructure.persistence.DocumentRepository;
import com.majm.rag.knowledge.application.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "doc", "md", "txt");

    private final KnowledgeBaseService kbService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final IngestionOutboxRepository outboxRepository;

    @Transactional
    public UploadDocumentResponse upload(UUID knowledgeBaseId, MultipartFile file) {
        validate(file);
        KnowledgeBase kb = kbService.getById(knowledgeBaseId);

        UUID docId = UUID.randomUUID();
        String displayName = file.getOriginalFilename();
        String path = storageService.store(file, knowledgeBaseId, docId);

        Document doc = new Document();
        doc.setId(docId);
        doc.setKnowledgeBase(kb);
        doc.setName(displayName);
        doc.setFileType(detectFileType(displayName));
        doc.setFilePath(path);
        // The outbox repository uses JDBC directly, so flush the JPA insert
        // before its foreign key is checked within the same transaction.
        return saveAndEnqueue(doc, knowledgeBaseId);
    }

    @Transactional
    public UploadDocumentResponse submitUrl(UUID knowledgeBaseId, URI url) {
        if (url == null) {
            throw new IllegalArgumentException("URL is required");
        }
        String source = url.toASCIIString();
        if (source.length() > 1024) {
            throw new IllegalArgumentException("URL is too long");
        }

        KnowledgeBase kb = kbService.getById(knowledgeBaseId);
        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        doc.setKnowledgeBase(kb);
        doc.setName(StringUtils.abbreviate(source, 255));
        doc.setFileType("URL");
        doc.setFilePath(source);
        return saveAndEnqueue(doc, knowledgeBaseId);
    }

    private UploadDocumentResponse saveAndEnqueue(Document doc, UUID knowledgeBaseId) {
        Document savedDoc = documentRepository.saveAndFlush(doc);
        outboxRepository.enqueue(savedDoc.getId(), knowledgeBaseId);
        return new UploadDocumentResponse(savedDoc.getId(), savedDoc.getName(),
            savedDoc.getStatus().name());
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        if (StringUtils.isBlank(file.getOriginalFilename())) {
            throw new IllegalArgumentException("Uploaded file has no name");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (StringUtils.isBlank(ext) || !ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type. Allowed: " + ALLOWED_EXTENSIONS);
        }
    }

    private String detectFileType(String filename) {
        String ext = extensionOf(filename);
        if (StringUtils.isBlank(ext)) {
            return "TXT";
        }
        return switch (ext) {
            case "pdf" -> "PDF";
            case "docx", "doc" -> "DOCX";
            case "md" -> "MD";
            default -> "TXT";
        };
    }

    /** Returns the lower-cased extension, or empty string when none. Null-safe. */
    private String extensionOf(String filename) {
        return StringUtils.lowerCase(FilenameUtils.getExtension(StringUtils.trimToEmpty(filename)));
    }
}
