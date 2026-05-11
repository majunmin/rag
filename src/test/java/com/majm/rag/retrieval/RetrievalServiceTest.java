package com.majm.rag.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock VectorStore vectorStore;
    @Mock RerankService rerankService;

    @InjectMocks RetrievalService service;

    private final UUID kbId = UUID.randomUUID();

    @BeforeEach
    void setupDefaults() {
        // Mirror application.yml defaults for the @Value fields.
        ReflectionTestUtils.setField(service, "defaultTopKRecall", 20);
        ReflectionTestUtils.setField(service, "rerankExpandFactor", 2);
        ReflectionTestUtils.setField(service, "scoreThreshold", -1.0);  // disabled
        ReflectionTestUtils.setField(service, "mmrLambda", 0.7);
        ReflectionTestUtils.setField(service, "mmrEnabled", true);
    }

    @Test
    void rejectsNullKb() {
        assertThatThrownBy(() -> service.search(null, "q", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("knowledgeBaseId");
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> service.search(kbId, "  ", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("query");
    }

    @Test
    void emptyRecallShortCircuitsRerank() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<Document> out = service.search(kbId, "q", 5);

        assertThat(out).isEmpty();
    }

    @Test
    void rerankReceivesExpandedTopKForMmrHeadroom() {
        // Recall returns 20; finalTopK=5 -> rerank should be asked for 10.
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(docs(20, 0.9));
        when(rerankService.rerank(anyString(), any(), anyInt()))
            .thenAnswer(inv -> ((List<Document>) inv.getArgument(1)).subList(0, (int) inv.getArgument(2)));

        service.search(kbId, "q", 5);

        ArgumentCaptor<Integer> topKCaptor = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(rerankService).rerank(any(), any(), topKCaptor.capture());
        assertThat(topKCaptor.getValue()).isEqualTo(10);
    }

    @Test
    void thresholdDropsLowScoreCandidates_returnsEmptyWhenAllBelow() {
        ReflectionTestUtils.setField(service, "scoreThreshold", 0.5);

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(docs(10, 0.4));
        when(rerankService.rerank(anyString(), any(), anyInt()))
            .thenReturn(scoredDocs(0.4, 0.3, 0.2));

        List<Document> out = service.search(kbId, "q", 5);

        // All below threshold -> empty so the LLM goes the "no answer" path.
        assertThat(out).isEmpty();
    }

    @Test
    void thresholdKeepsCandidatesAtOrAboveCutoff() {
        ReflectionTestUtils.setField(service, "scoreThreshold", 0.5);
        ReflectionTestUtils.setField(service, "mmrEnabled", false);    // isolate threshold logic

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(docs(10, 0.6));
        when(rerankService.rerank(anyString(), any(), anyInt()))
            .thenReturn(scoredDocs(0.9, 0.6, 0.49, 0.3));

        List<Document> out = service.search(kbId, "q", 5);

        // 0.49 and 0.3 are below 0.5; 0.9 and 0.6 stay.
        assertThat(out).hasSize(2);
    }

    @Test
    void candidatesWithoutRerankScore_areKept_whenThresholdActive() {
        // NoOpRerankService / fail-soft fallback returns chunks without
        // rerank_score; we should not silently drop them.
        ReflectionTestUtils.setField(service, "scoreThreshold", 0.5);
        ReflectionTestUtils.setField(service, "mmrEnabled", false);

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(docs(3, 0.0));
        when(rerankService.rerank(anyString(), any(), anyInt()))
            .thenReturn(List.of(
                new Document("a"), new Document("b"), new Document("c")));

        List<Document> out = service.search(kbId, "q", 5);

        assertThat(out).hasSize(3);
    }

    private static List<Document> docs(int n, double score) {
        return IntStream.range(0, n)
            .mapToObj(i -> new Document("chunk-" + i, Map.of("rerank_score", score)))
            .toList();
    }

    private static List<Document> scoredDocs(double... scores) {
        return IntStream.range(0, scores.length)
            .mapToObj(i -> new Document("chunk-" + i + "-content with enough text to be distinct " + i,
                Map.of("rerank_score", scores[i])))
            .toList();
    }
}
