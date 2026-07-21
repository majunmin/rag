package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.UploadDocumentResponse;
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
