package com.majm.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpRerankServiceTest {

    private final NoOpRerankService service = new NoOpRerankService();

    @Test
    void returnsFirstTopK_whenInputLargerThanTopK() {
        List<Document> input = docs(10);
        List<Document> result = service.rerank("anything", input, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getText()).isEqualTo("chunk-0");
        assertThat(result.get(1).getText()).isEqualTo("chunk-1");
        assertThat(result.get(2).getText()).isEqualTo("chunk-2");
    }

    @Test
    void returnsAll_whenInputSmallerThanTopK() {
        List<Document> input = docs(3);
        List<Document> result = service.rerank("anything", input, 10);

        assertThat(result).hasSize(3);
    }

    @Test
    void returnsEmpty_whenInputEmpty() {
        assertThat(service.rerank("q", List.of(), 5)).isEmpty();
    }

    @Test
    void returnsEmpty_whenInputNull() {
        assertThat(service.rerank("q", null, 5)).isEmpty();
    }

    @Test
    void returnsEmpty_whenTopKZero() {
        assertThat(service.rerank("q", docs(5), 0)).isEmpty();
    }

    @Test
    void preservesOrder() {
        List<Document> input = docs(5);
        List<Document> result = service.rerank("q", input, 5);

        for (int i = 0; i < input.size(); i++) {
            assertThat(result.get(i).getText()).isEqualTo(input.get(i).getText());
        }
    }

    private static List<Document> docs(int n) {
        return IntStream.range(0, n)
            .mapToObj(i -> new Document("chunk-" + i))
            .toList();
    }
}
