package com.majm.rag.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;

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
    void create_caseInsensitive_returnsTikaReader() {
        DocumentReader reader = factory.create("pdf", "/some/file.pdf");
        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void create_urlType_isRejected() {
        assertThatThrownBy(() -> factory.create("URL", "https://example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("URL");
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
}
