package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.UploadDocumentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebDocumentServiceTest {

    @Mock private WebUrlPolicy urlPolicy;
    @Mock private DocumentUploadService uploadService;
    @InjectMocks private WebDocumentService webDocumentService;

    @Test
    void submit_validatesUrlBeforePersistingDocument() {
        UUID kbId = UUID.randomUUID();
        URI normalized = URI.create("https://example.com/guide");
        UploadDocumentResponse expected =
            new UploadDocumentResponse(UUID.randomUUID(), normalized.toString(), "PENDING");
        when(urlPolicy.validate(" https://example.com/guide#intro ")).thenReturn(normalized);
        when(uploadService.submitUrl(kbId, normalized)).thenReturn(expected);

        UploadDocumentResponse result =
            webDocumentService.submit(kbId, " https://example.com/guide#intro ");

        assertThat(result).isEqualTo(expected);
        verify(urlPolicy).validate(" https://example.com/guide#intro ");
        verify(uploadService).submitUrl(kbId, normalized);
    }
}
