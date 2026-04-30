package com.majm.rag.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@Slf4j
public class LocalStorageService implements StorageService {

    @Value("${app.storage.base-path:/data/uploads}")
    private String basePath;

    @Override
    public String store(MultipartFile file, UUID knowledgeBaseId, UUID documentId) {
        try {
            Path dir = Path.of(basePath, knowledgeBaseId.toString(), documentId.toString());
            Files.createDirectories(dir);
            Path dest = dir.resolve(file.getOriginalFilename());
            file.transferTo(dest);
            return dest.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file", e);
        }
    }

    @Override
    public void delete(String filePath) {
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (IOException e) {
            log.warn("Could not delete file: {}", filePath, e);
        }
    }
}
