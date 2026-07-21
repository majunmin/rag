package com.majm.rag.ingestion.infrastructure.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebUrlPolicyTest {

    private final WebUrlPolicy policy = new WebUrlPolicy();

    @Test
    void validate_acceptsPublicHttpUrlAndDropsFragment() {
        URI result = policy.validate(" https://93.184.216.34/guide?q=rag#section ");

        assertThat(result.toString()).isEqualTo("https://93.184.216.34/guide?q=rag");
    }

    @Test
    void validate_preservesEncodedPathAndQuery() {
        URI result = policy.validate(
            "https://93.184.216.34/guides/RAG%20Basics?q=%E4%B8%AD%E6%96%87#section");

        assertThat(result.toASCIIString()).isEqualTo(
            "https://93.184.216.34/guides/RAG%20Basics?q=%E4%B8%AD%E6%96%87");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd",
        "ftp://93.184.216.34/file",
        "https://user:password@93.184.216.34/private"
    })
    void validate_rejectsUnsupportedOrCredentialedUrl(String url) {
        assertThatThrownBy(() -> policy.validate(url))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1/admin",
        "http://10.0.0.1/internal",
        "http://169.254.169.254/latest/meta-data",
        "http://[::1]/admin",
        "http://[fc00::1]/internal"
    })
    void validate_rejectsNonPublicTargets(String url) {
        assertThatThrownBy(() -> policy.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("public");
    }
}
