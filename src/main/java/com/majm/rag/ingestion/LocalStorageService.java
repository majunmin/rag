package com.majm.rag.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class LocalStorageService implements StorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "doc", "md", "txt");
    private static final String DEFAULT_EXTENSION = "txt";

    @Value("${app.storage.base-path:/data/uploads}")
    private String basePath;

    @Override
    public String store(MultipartFile file, UUID knowledgeBaseId, UUID documentId) {
        try {
            Path dir = Path.of(basePath, knowledgeBaseId.toString());
            Files.createDirectories(dir);
            String extension = sanitizedExtension(file.getOriginalFilename());
            Path dest = dir.resolve(documentId + "." + extension);
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

    private String sanitizedExtension(String originalFilename) {
        if (originalFilename == null) {
            return DEFAULT_EXTENSION;
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return DEFAULT_EXTENSION;
        }
        String raw = originalFilename.substring(dot + 1).toLowerCase();
        if (!raw.matches("[a-z0-9]{1,8}")) {
            return DEFAULT_EXTENSION;
        }
        return ALLOWED_EXTENSIONS.contains(raw) ? raw : DEFAULT_EXTENSION;
    }
}
