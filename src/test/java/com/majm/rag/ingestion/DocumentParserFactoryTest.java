package com.majm.rag.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.UrlResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserFactoryTest {

    private final DocumentParserFactory factory = new DocumentParserFactory();

    @Test
    void create_pdfType_returnsTikaReader() {
        DocumentReader reader = factory.create("PDF", "/some/file.pdf");
        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void create_docxType_returnsTikaReader() {
        DocumentReader reader = factory.create("DOCX", "/some/file.docx");
        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void create_mdType_returnsTextReader() {
        DocumentReader reader = factory.create("MD", "/some/file.md");
        assertThat(reader).isInstanceOf(TextReader.class);
    }

    @Test
    void create_txtType_returnsTextReader() {
        DocumentReader reader = factory.create("TXT", "/some/file.txt");
        assertThat(reader).isInstanceOf(TextReader.class);
    }

    @Test
    void create_urlType_returnsUrlResource() {
        DocumentReader reader = factory.create("URL", "https://example.com");
        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void create_urlTypeMalformed_throwsException() {
        assertThatThrownBy(() -> factory.create("URL", "not-a-url"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_caseInsensitive_returnsTikaReader() {
        DocumentReader reader = factory.create("pdf", "/some/file.pdf");
        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void create_unknownType_throwsException() {
        assertThatThrownBy(() -> factory.create("XLS", "/some/file.xls"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XLS");
    }
}
