package com.majm.rag.knowledge.application.port;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface StorageService {
    String store(MultipartFile file, UUID knowledgeBaseId, UUID documentId);
    void delete(String filePath);
}
