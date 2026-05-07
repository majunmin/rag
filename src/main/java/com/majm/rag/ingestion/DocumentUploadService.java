package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.ingestion.dto.UploadDocumentResponse;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "doc", "md", "txt");

    private final KnowledgeBaseService kbService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.ingestion.topic:document.ingestion}")
    private String ingestionTopic;

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
        Document savedDoc = documentRepository.save(doc);

        kafkaTemplate.send(ingestionTopic, docId.toString(),
            new IngestionMessage(docId, knowledgeBaseId));

        return new UploadDocumentResponse(savedDoc.getId(), savedDoc.getName(),
            savedDoc.getStatus().name());
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Uploaded file has no name");
        }
        String ext = extensionOf(name);
        if (ext == null || !ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type. Allowed: " + ALLOWED_EXTENSIONS);
        }
    }

    private String detectFileType(String filename) {
        String ext = extensionOf(filename);
        if (ext == null) {
            return "TXT";
        }
        return switch (ext) {
            case "pdf" -> "PDF";
            case "docx", "doc" -> "DOCX";
            case "md" -> "MD";
            default -> "TXT";
        };
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
