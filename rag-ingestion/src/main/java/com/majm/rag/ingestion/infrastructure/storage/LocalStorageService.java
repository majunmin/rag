package com.majm.rag.ingestion.infrastructure.storage;

import com.majm.rag.knowledge.application.port.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
public class LocalStorageService implements StorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "doc", "md", "txt");
    private static final String DEFAULT_EXTENSION = "txt";
    private static final Pattern SAFE_EXT_PATTERN = Pattern.compile("[a-z0-9]{1,8}");

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
        if (StringUtils.isBlank(filePath)) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (IOException e) {
            log.warn("Could not delete file: {}", filePath, e);
        }
    }

    /**
     * Returns a safe lower-cased extension constrained to the allow-list, or
     * {@link #DEFAULT_EXTENSION} when the input is null/blank/unrecognized.
     * Uses Commons FilenameUtils so we don't have to think about path
     * separators or "..".
     */
    private String sanitizedExtension(String originalFilename) {
        String raw = StringUtils.lowerCase(FilenameUtils.getExtension(StringUtils.trimToEmpty(originalFilename)));
        if (StringUtils.isBlank(raw) || !SAFE_EXT_PATTERN.matcher(raw).matches()) {
            return DEFAULT_EXTENSION;
        }
        return ALLOWED_EXTENSIONS.contains(raw) ? raw : DEFAULT_EXTENSION;
    }
}
