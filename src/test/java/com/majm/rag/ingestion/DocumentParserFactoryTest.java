package com.majm.rag.ingestion;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentParserFactoryTest {

    @TempDir Path tempDir;

    private final WebPageCrawler webPageCrawler = mock(WebPageCrawler.class);
    private final DocumentParserFactory factory = new DocumentParserFactory(webPageCrawler);

    @Test
    void create_pdfType_returnsPagePdfReader() throws IOException {
        DocumentReader reader = factory.create("PDF", createPdf().toString());
        assertThat(reader).isInstanceOf(PagePdfDocumentReader.class);
    }

    @Test
    void create_docxType_returnsTikaReader() {
        DocumentReader reader = factory.create("DOCX", "/some/file.docx");
        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void create_mdType_returnsMarkdownReader() {
        DocumentReader reader = factory.create("MD", "/some/file.md");
        assertThat(reader).isInstanceOf(MarkdownDocumentReader.class);
    }

    @Test
    void create_txtType_returnsTextReader() {
        DocumentReader reader = factory.create("TXT", "/some/file.txt");
        assertThat(reader).isInstanceOf(TextReader.class);
    }

    @Test
    void pdfReader_extractsTextByPage() throws IOException {
        DocumentReader reader = factory.create("PDF", createPdf("Spring AI PDF reader").toString());

        assertThat(reader.get())
            .singleElement()
            .satisfies(document -> {
                assertThat(StringUtils.normalizeSpace(document.getText()))
                    .contains("Spring AI PDF reader");
                assertThat(document.getMetadata()).containsEntry("page_number", 1);
            });
    }

    @Test
    void markdownReader_keepsCodeBlocksAndBlockquotes() throws IOException {
        Path markdown = tempDir.resolve("guide.md");
        Files.writeString(markdown, """
            # Guide

            > Keep this operational warning.

            ```java
            System.out.println("keep code");
            ```
            """);

        List<Document> documents = factory.create("MD", markdown.toString()).get();
        String text = documents.stream().map(Document::getText).collect(Collectors.joining("\n"));

        assertThat(text).contains("Keep this operational warning", "System.out.println");
    }

    @Test
    void create_caseInsensitive_returnsPagePdfReader() throws IOException {
        DocumentReader reader = factory.create("pdf", createPdf().toString());
        assertThat(reader).isInstanceOf(PagePdfDocumentReader.class);
    }

    @Test
    void create_urlType_crawlsWhenReaderIsConsumed() {
        String url = "https://example.com/guide";
        List<org.springframework.ai.document.Document> crawled =
            List.of(new org.springframework.ai.document.Document("web content"));
        when(webPageCrawler.crawl(url)).thenReturn(crawled);

        DocumentReader reader = factory.create("URL", url);

        verifyNoInteractions(webPageCrawler);
        assertThat(reader.get()).isEqualTo(crawled);
        verify(webPageCrawler).crawl(url);
    }

    @Test
    void create_unknownType_throwsException() {
        assertThatThrownBy(() -> factory.create("XLS", "/some/file.xls"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XLS");
    }

    @Test
    void create_nullType_throwsException() {
        assertThatThrownBy(() -> factory.create(null, "/some/file"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Path createPdf() throws IOException {
        return createPdf(null);
    }

    private Path createPdf(String text) throws IOException {
        Path path = tempDir.resolve("test.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            if (text != null) {
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 720);
                    content.showText(text);
                    content.endText();
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }
}
