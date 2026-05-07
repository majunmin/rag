package com.majm.rag.ingestion;

import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@Component
public class DocumentParserFactory {

    public DocumentReader create(String fileType, String filePath) {
        if (fileType == null) {
            throw new IllegalArgumentException("fileType is required");
        }
        return switch (fileType.toUpperCase()) {
            case "PDF", "DOCX" -> new TikaDocumentReader(new FileSystemResource(filePath));
            case "MD", "TXT"   -> new TextReader(new FileSystemResource(filePath));
            default -> throw new IllegalArgumentException("Unsupported file type: " + fileType);
        };
    }
}
