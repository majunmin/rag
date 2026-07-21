package com.majm.rag.ingestion;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class WebPageCrawler {

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> HTML_CONTENT_TYPES = Set.of("text/html", "application/xhtml+xml");

    private final WebUrlPolicy urlPolicy;
    private final HttpClient httpClient;

    @Autowired
    public WebPageCrawler(WebUrlPolicy urlPolicy) {
        this(urlPolicy, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
    }

    WebPageCrawler(WebUrlPolicy urlPolicy, HttpClient httpClient) {
        this.urlPolicy = urlPolicy;
        this.httpClient = httpClient;
    }

    public List<Document> crawl(String rawUrl) {
        URI current = urlPolicy.validate(rawUrl);
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            HttpResponse<InputStream> response = send(current);
            if (REDIRECT_STATUSES.contains(response.statusCode())) {
                close(response.body());
                if (redirectCount == MAX_REDIRECTS) {
                    throw new IllegalArgumentException("Web page redirected too many times");
                }
                String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalArgumentException("Web page redirect has no location"));
                current = urlPolicy.validate(current.resolve(location).toString());
                continue;
            }
            return parse(current, response);
        }
        throw new IllegalStateException("Unreachable redirect state");
    }

    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "RagDocumentCrawler/1.0")
            .GET()
            .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Web page fetch was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not fetch web page", e);
        }
    }

    private List<Document> parse(
        URI uri, HttpResponse<InputStream> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            close(response.body());
            throw new IllegalArgumentException("Web page returned HTTP " + response.statusCode());
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        MediaType mediaType = parseMediaType(contentType);
        String mimeType = mediaType.getType().toLowerCase(Locale.ROOT) + "/"
            + mediaType.getSubtype().toLowerCase(Locale.ROOT);
        if (!HTML_CONTENT_TYPES.contains(mimeType)) {
            close(response.body());
            throw new IllegalArgumentException("URL must return an HTML document");
        }

        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declaredLength > MAX_BODY_BYTES) {
            close(response.body());
            throw new IllegalArgumentException("Web page is too large");
        }

        byte[] body = readBody(response.body());
        Charset charset = mediaType.getCharset() == null ? StandardCharsets.UTF_8 : mediaType.getCharset();
        JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder()
            .charset(charset.name())
            .allElements(true)
            .additionalMetadata("source", "web")
            .additionalMetadata("url", uri.toString())
            .build();
        List<Document> documents = new JsoupDocumentReader(new ByteArrayResource(body), config).get();
        if (documents.isEmpty() || documents.stream().allMatch(doc -> StringUtils.isBlank(doc.getText()))) {
            throw new IllegalArgumentException("Web page contains no readable text");
        }
        documents.forEach(document -> {
            Object title = document.getMetadata().get("title");
            if (!(title instanceof String value) || StringUtils.isBlank(value)) {
                document.getMetadata().put("title", uri.toString());
            }
        });
        return documents;
    }

    private MediaType parseMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            throw new IllegalArgumentException("URL must return an HTML document", e);
        }
    }

    private byte[] readBody(InputStream input) {
        try (input) {
            byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("Web page is too large");
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read web page", e);
        }
    }

    private void close(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The response is already being rejected; closing is best effort.
        }
    }
}
