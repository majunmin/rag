package com.majm.rag.ingestion.infrastructure.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebPageCrawlerTest {

    private static final String URL = "https://93.184.216.34/guide";

    @Mock private WebUrlPolicy urlPolicy;
    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<InputStream> response;

    private WebPageCrawler crawler;

    @BeforeEach
    void setUp() {
        crawler = new WebPageCrawler(urlPolicy, httpClient);
    }

    @Test
    void crawl_extractsVisibleTextAndSourceMetadata() throws Exception {
        URI uri = URI.create(URL);
        when(urlPolicy.validate(URL)).thenReturn(uri);
        stubResponse(200, Map.of("Content-Type", List.of("text/html; charset=utf-8")), """
            <html>
              <head>
                <title>RAG Guide</title>
                <meta name="description" content="A guide to web ingestion">
                <style>.hidden { display:none }</style>
              </head>
              <body><h1>Web ingestion</h1><p>Visible content.</p><script>alert('x')</script></body>
            </html>
            """);

        List<org.springframework.ai.document.Document> documents = crawler.crawl(URL);

        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.getText())
                .contains("Web ingestion", "Visible content.")
                .doesNotContain("alert('x')", ".hidden", "<h1>");
            assertThat(document.getMetadata())
                .containsEntry("source", "web")
                .containsEntry("url", URL)
                .containsEntry("title", "RAG Guide")
                .containsEntry("description", "A guide to web ingestion");
        });
    }

    @Test
    void crawl_rejectsNonHtmlResponse() throws Exception {
        when(urlPolicy.validate(URL)).thenReturn(URI.create(URL));
        stubResponse(200, Map.of("Content-Type", List.of("application/pdf")), "%PDF");

        assertThatThrownBy(() -> crawler.crawl(URL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTML");
    }

    @Test
    void crawl_rejectsOversizedResponseBeforeReadingBody() throws Exception {
        when(urlPolicy.validate(URL)).thenReturn(URI.create(URL));
        stubResponse(200, Map.of(
            "Content-Type", List.of("text/html"),
            "Content-Length", List.of("5242881")
        ), "<html></html>");

        assertThatThrownBy(() -> crawler.crawl(URL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("large");
    }

    @Test
    void crawl_revalidatesRedirectTargetBeforeFollowing() throws Exception {
        String privateUrl = "http://127.0.0.1/admin";
        when(urlPolicy.validate(URL)).thenReturn(URI.create(URL));
        when(urlPolicy.validate(privateUrl))
            .thenThrow(new IllegalArgumentException("URL must resolve only to public addresses"));
        stubResponse(302, Map.of("Location", List.of(privateUrl)), "");

        assertThatThrownBy(() -> crawler.crawl(URL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("public");
        verify(httpClient).send(any(HttpRequest.class), anyInputStreamHandler());
        verifyNoMoreInteractions(httpClient);
    }

    private void stubResponse(int status, Map<String, List<String>> headers, String body)
        throws Exception {
        when(httpClient.send(any(HttpRequest.class), anyInputStreamHandler())).thenReturn(response);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse.BodyHandler<InputStream> anyInputStreamHandler() {
        return any();
    }
}
