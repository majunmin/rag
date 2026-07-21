package com.majm.rag.ingestion.infrastructure.document;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class TikaDependencyCompatibilityTest {

    @Test
    void commonsCompressCanCreateTarEntryWithResolvedCommonsLang() {
        assertThatCode(() -> new TarArchiveEntry("document.txt"))
            .doesNotThrowAnyException();
    }
}
