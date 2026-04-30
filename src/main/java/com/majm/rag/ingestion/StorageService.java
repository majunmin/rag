package com.majm.rag.ingestion;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface StorageService {
    String store(MultipartFile file, UUID knowledgeBaseId, UUID documentId);
    void delete(String filePath);
}
