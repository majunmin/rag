package com.majm.rag.ingestion;

import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;

@Component
public class DocumentParserFactory {

    public DocumentReader create(String fileType, String filePath) {
        return switch (fileType.toUpperCase()) {
            case "PDF", "DOCX" -> new TikaDocumentReader(new FileSystemResource(filePath));
            case "MD", "TXT"   -> new TextReader(new FileSystemResource(filePath));
            case "URL"         -> {
                try {
                    yield new TikaDocumentReader(new UrlResource(filePath));
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException("Invalid URL: " + filePath, e);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported file type: " + fileType);
        };
    }
}
