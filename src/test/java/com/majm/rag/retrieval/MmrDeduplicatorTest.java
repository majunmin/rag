package com.majm.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MmrDeduplicatorTest {

    @Test
    void returnsInputWhenItsSmallerThanTopK() {
        List<Document> in = List.of(doc("alpha", 0.9), doc("beta", 0.8));
        assertThat(MmrDeduplicator.apply(in, 5, 0.7)).hasSize(2);
    }

    @Test
    void emptyOrZeroTopKYieldsEmpty() {
        assertThat(MmrDeduplicator.apply(List.of(), 5, 0.7)).isEmpty();
        assertThat(MmrDeduplicator.apply(null, 5, 0.7)).isEmpty();
        assertThat(MmrDeduplicator.apply(List.of(doc("a", 0.9)), 0, 0.7)).isEmpty();
    }

    @Test
    void nearDuplicatesAreDropped_whenLambdaIsModerate() {
        String base = "Spring Boot auto configuration enables conventions over configuration.";
        String dup  = "Spring Boot auto configuration enables conventions over configuration! ";
        String distinct = "Kafka partitions are the unit of parallelism for consumers.";

        // Rerank returned 3 candidates; two are near-identical.
        List<Document> in = List.of(
            doc(base, 0.95),
            doc(dup,  0.94),
            doc(distinct, 0.70)
        );

        List<Document> out = MmrDeduplicator.apply(in, 2, 0.6);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getText()).isEqualTo(base);
        // The near-duplicate should lose out to the distinct chunk at lambda=0.6.
        assertThat(out.get(1).getText()).isEqualTo(distinct);
    }

    @Test
    void lambdaOneIgnoresDiversity() {
        String base = "Spring Boot auto configuration.";
        String dup  = "Spring Boot auto configuration! ";
        String distinct = "Kafka partitions.";

        List<Document> in = List.of(
            doc(base, 0.95),
            doc(dup,  0.90),
            doc(distinct, 0.20)
        );

        // lambda=1.0 -> pure relevance: top 2 are base + dup even though
        // they're redundant.
        List<Document> out = MmrDeduplicator.apply(in, 2, 1.0);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getText()).isEqualTo(base);
        assertThat(out.get(1).getText()).isEqualTo(dup);
    }

    @Test
    void lambdaZeroMaximisesDiversity_pickingDistinctSecond() {
        String base = "Spring Boot auto configuration enables conventions.";
        String dup  = "Spring Boot auto configuration enables conventions!";
        String distinct = "Kafka partitions are the unit of parallelism.";

        List<Document> in = List.of(
            doc(base, 0.95),
            doc(dup,  0.94),
            doc(distinct, 0.30)
        );

        List<Document> out = MmrDeduplicator.apply(in, 2, 0.0);

        // lambda=0 -> diversity dominates; second pick must not be the near-dup.
        assertThat(out).hasSize(2);
        assertThat(out.get(1).getText()).isEqualTo(distinct);
    }

    @Test
    void clampsLambdaOutsideZeroOne() {
        List<Document> in = List.of(doc("a", 0.9), doc("b", 0.8), doc("c", 0.7));
        assertThat(MmrDeduplicator.apply(in, 2, -5.0)).hasSize(2);
        assertThat(MmrDeduplicator.apply(in, 2,  5.0)).hasSize(2);
    }

    @Test
    void fallsBackToPositionalRelevance_whenMetadataMissing() {
        // No rerank_score metadata — apply should still produce a stable
        // ordering by input position.
        List<Document> in = List.of(
            new Document("first chunk"),
            new Document("second chunk very different text"),
            new Document("third entirely different content here")
        );
        List<Document> out = MmrDeduplicator.apply(in, 2, 0.7);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getText()).isEqualTo("first chunk");
    }

    private static Document doc(String text, double rerankScore) {
        return new Document(text, Map.of("rerank_score", rerankScore));
    }
}
