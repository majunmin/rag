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

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private final KnowledgeBaseService kbService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.ingestion.topic:document.ingestion}")
    private String ingestionTopic;

    @Transactional
    public UploadDocumentResponse upload(UUID knowledgeBaseId, MultipartFile file) {
        KnowledgeBase kb = kbService.getById(knowledgeBaseId);

        Document doc = new Document();
        doc.setKnowledgeBase(kb);
        doc.setName(file.getOriginalFilename());
        doc.setFileType(detectFileType(file.getOriginalFilename()));
        Document savedDoc = documentRepository.save(doc);

        String path = storageService.store(file, knowledgeBaseId, savedDoc.getId());
        savedDoc.setFilePath(path);
        documentRepository.save(savedDoc);

        kafkaTemplate.send(ingestionTopic, savedDoc.getId().toString(),
            new IngestionMessage(savedDoc.getId(), knowledgeBaseId));

        return new UploadDocumentResponse(savedDoc.getId(), savedDoc.getName(),
            savedDoc.getStatus().name());
    }

    private String detectFileType(String filename) {
        if (filename == null) return "TXT";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "DOCX";
        if (lower.endsWith(".md")) return "MD";
        if (lower.startsWith("http://") || lower.startsWith("https://")) return "URL";
        return "TXT";
    }
}
