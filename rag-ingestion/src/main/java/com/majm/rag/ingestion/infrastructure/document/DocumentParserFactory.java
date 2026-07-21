package com.majm.rag.ingestion.infrastructure.document;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentParserFactory {

    private static final MarkdownDocumentReaderConfig MARKDOWN_CONFIG =
        MarkdownDocumentReaderConfig.builder()
            .withIncludeCodeBlock(true)
            .withIncludeBlockquote(true)
            .build();

    private final WebPageCrawler webPageCrawler;

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
            case "PDF"  -> new PagePdfDocumentReader(new FileSystemResource(filePath));
            case "DOCX" -> new TikaDocumentReader(new FileSystemResource(filePath));
            case "MD"   -> new MarkdownDocumentReader(
                new FileSystemResource(filePath), MARKDOWN_CONFIG);
            case "TXT"  -> new TextReader(new FileSystemResource(filePath));
            case "URL"  -> () -> webPageCrawler.crawl(filePath);
            default -> throw new IllegalArgumentException("Unsupported file type: " + fileType);
        };
    }
}
