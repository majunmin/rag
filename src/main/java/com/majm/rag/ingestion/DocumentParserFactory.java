package com.majm.rag.ingestion;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@Component
public class DocumentParserFactory {

    public DocumentReader create(String fileType, String filePath) {
        // We deliberately throw IllegalArgumentException (not NPE from
        // Validate.notBlank) so GlobalExceptionHandler maps these to 400.
        if (StringUtils.isBlank(fileType)) {
            throw new IllegalArgumentException("fileType is required");
        }
        if (StringUtils.isBlank(filePath)) {
            throw new IllegalArgumentException("filePath is required");
        }
        return switch (StringUtils.upperCase(fileType)) {
            case "PDF", "DOCX" -> new TikaDocumentReader(new FileSystemResource(filePath));
            case "MD", "TXT"   -> new TextReader(new FileSystemResource(filePath));
            default -> throw new IllegalArgumentException("Unsupported file type: " + fileType);
        };
    }
}
