package com.majm.rag.retrieval.application;

import com.majm.rag.retrieval.rerank.RerankService;
import com.majm.rag.retrieval.rewrite.RewriteResult;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
        ReflectionTestUtils.setField(service, "rrfK", 60);
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

    @Test
    void rewriteOverload_passthroughBehavesLikeLegacyArity() {
        // Old arity and new arity with passthrough rewrite should be observationally equivalent.
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(docs(3, 0.9));
        when(rerankService.rerank(anyString(), any(), anyInt()))
            .thenAnswer(inv -> ((List<Document>) inv.getArgument(1))
                .subList(0, Math.min((int) inv.getArgument(2), ((List<Document>) inv.getArgument(1)).size())));

        List<Document> legacy = service.search(kbId, "q", 3);
        List<Document> overload = service.search(kbId, "q", 3, RewriteResult.passthrough("q"));

        assertThat(legacy).extracting(Document::getText)
            .isEqualTo(overload.stream().map(Document::getText).toList());
    }

    @Test
    void hydeEmbeddingQuery_drivesVectorSearch_originalDrivesRerank() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(docs(3, 0.9));
        when(rerankService.rerank(anyString(), any(), anyInt()))
            .thenAnswer(inv -> ((List<Document>) inv.getArgument(1))
                .subList(0, Math.min((int) inv.getArgument(2), ((List<Document>) inv.getArgument(1)).size())));

        RewriteResult hyde = new RewriteResult("original question?", "Hypothetical answer passage.", List.of());

        service.search(kbId, "original question?", 3, hyde);

        // VectorStore receives the HyDE text
        ArgumentCaptor<SearchRequest> req = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(req.capture());
        assertThat(req.getValue().getQuery()).isEqualTo("Hypothetical answer passage.");

        // Rerank sees the original (the user's real intent)
        ArgumentCaptor<String> rerankQ = ArgumentCaptor.forClass(String.class);
        verify(rerankService).rerank(rerankQ.capture(), any(), anyInt());
        assertThat(rerankQ.getValue()).isEqualTo("original question?");
    }

    @Test
    void multiQueryExpansion_runsOnceRecallPerQuery_andFusesWithRrf() {
        // 3 queries: original + 2 paraphrases. Each returns 3 overlapping docs with different ranks.
        // Doc A:  rank 0 in q1,  rank 2 in q2,  not in q3   -> 1/(60+1) + 1/(60+3) + 0      = 0.01639+0.01587 = 0.03226
        // Doc B:  rank 1 in q1,  rank 0 in q2,  rank 1 in q3-> 1/62 + 1/61 + 1/62           = 0.01613+0.01639+0.01613 = 0.04865 (top)
        // Doc C:  rank 2 in q1,  rank 1 in q2,  rank 0 in q3-> 1/63 + 1/62 + 1/61           = 0.01587+0.01613+0.01639 = 0.04839
        Document a = new Document("doc-A", "A content", Map.of());
        Document b = new Document("doc-B", "B content", Map.of());
        Document c = new Document("doc-C", "C content", Map.of());

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(List.of(a, b, c), List.of(b, c, a), List.of(c, b));
        when(rerankService.rerank(anyString(), any(), anyInt()))
            .thenAnswer(inv -> inv.getArgument(1));

        RewriteResult multi = new RewriteResult("q", "q", List.of("paraphrase1", "paraphrase2"));

        List<Document> out = service.search(kbId, "q", 3, multi);

        verify(vectorStore, times(3)).similaritySearch(any(SearchRequest.class));
        // B wins the fusion (best aggregate rank), C second, A third.
        assertThat(out).extracting(Document::getId).containsExactly("doc-B", "doc-C", "doc-A");
    }

    @Test
    void multiQueryExpansion_emptyResults_returnsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RewriteResult multi = new RewriteResult("q", "q", List.of("p1", "p2"));

        List<Document> out = service.search(kbId, "q", 3, multi);

        assertThat(out).isEmpty();
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
