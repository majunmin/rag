package com.majm.rag.ingestion.application;

import com.majm.rag.ingestion.api.dto.UploadDocumentResponse;
import com.majm.rag.ingestion.infrastructure.document.WebUrlPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebDocumentService {

    private final WebUrlPolicy urlPolicy;
    private final DocumentUploadService uploadService;

    public UploadDocumentResponse submit(UUID knowledgeBaseId, String rawUrl) {
        URI url = urlPolicy.validate(rawUrl);
        return uploadService.submitUrl(knowledgeBaseId, url);
    }
}
