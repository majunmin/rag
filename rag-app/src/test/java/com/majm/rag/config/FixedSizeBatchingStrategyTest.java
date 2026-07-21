package com.majm.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedSizeBatchingStrategyTest {

    @Test
    void splitsExactMultiple() {
        var strategy = new FixedSizeBatchingStrategy(10);
        List<List<Document>> batches = strategy.batch(docs(20));

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(10);
        assertThat(batches.get(1)).hasSize(10);
    }

    @Test
    void splitsWithRemainder() {
        var strategy = new FixedSizeBatchingStrategy(10);
        List<List<Document>> batches = strategy.batch(docs(23));

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(10);
        assertThat(batches.get(1)).hasSize(10);
        assertThat(batches.get(2)).hasSize(3);
    }

    @Test
    void singleBatchWhenUnderLimit() {
        var strategy = new FixedSizeBatchingStrategy(10);
        List<List<Document>> batches = strategy.batch(docs(7));

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(7);
    }

    @Test
    void emptyInputReturnsEmpty() {
        var strategy = new FixedSizeBatchingStrategy(10);

        assertThat(strategy.batch(List.of())).isEmpty();
        assertThat(strategy.batch(null)).isEmpty();
    }

    @Test
    void rejectsZeroOrNegativeSize() {
        assertThatThrownBy(() -> new FixedSizeBatchingStrategy(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedSizeBatchingStrategy(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesOrderAcrossBatches() {
        var strategy = new FixedSizeBatchingStrategy(3);
        List<Document> input = docs(7);
        List<List<Document>> batches = strategy.batch(input);

        List<Document> flattened = new ArrayList<>();
        batches.forEach(flattened::addAll);
        assertThat(flattened).containsExactlyElementsOf(input);
    }

    private static List<Document> docs(int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new Document("chunk-" + i))
            .toList();
    }
}
