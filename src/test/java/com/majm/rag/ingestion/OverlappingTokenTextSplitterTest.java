package com.majm.rag.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverlappingTokenTextSplitterTest {

    private static Encoding encoding;

    @BeforeAll
    static void setupEncoder() {
        encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new OverlappingTokenTextSplitter(0, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chunkSize");
        assertThatThrownBy(() -> new OverlappingTokenTextSplitter(100, -1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chunkOverlap");
        assertThatThrownBy(() -> new OverlappingTokenTextSplitter(100, 100))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be <");
        assertThatThrownBy(() -> new OverlappingTokenTextSplitter(100, 200))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be <");
    }

    @Test
    void shortTextProducesSingleChunk() {
        var splitter = new OverlappingTokenTextSplitter(512, 64);
        List<Document> chunks = splitter.apply(List.of(new Document("Hello, world.")));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo("Hello, world.");
    }

    @Test
    void longTextProducesMultipleChunksWithOverlap() {
        // 600 tokens of repeated text, chunkSize=200, overlap=50 → step=150
        // Expected chunks: [0..200], [150..350], [300..500], [450..600] = 4 chunks
        String longText = repeatTokens(600);
        var splitter = new OverlappingTokenTextSplitter(200, 50);

        List<Document> chunks = splitter.apply(List.of(new Document(longText)));

        assertThat(chunks).hasSize(4);
        for (Document chunk : chunks) {
            int tokenCount = encoding.countTokens(chunk.getText());
            assertThat(tokenCount).isLessThanOrEqualTo(200);
        }
        // Last chunk is the trailing remainder; everything else is exactly chunkSize tokens.
        for (int i = 0; i < 3; i++) {
            assertThat(encoding.countTokens(chunks.get(i).getText())).isEqualTo(200);
        }
    }

    @Test
    void zeroOverlapMeansContiguousNonRedundantChunks() {
        String longText = repeatTokens(400);
        var splitter = new OverlappingTokenTextSplitter(100, 0);

        List<Document> chunks = splitter.apply(List.of(new Document(longText)));

        assertThat(chunks).hasSize(4);
        // Concat without overlap should reproduce the original encoded length.
        int sumTokens = chunks.stream()
            .mapToInt(c -> encoding.countTokens(c.getText()))
            .sum();
        assertThat(sumTokens).isEqualTo(400);
    }

    @Test
    void overlapIsExactlyChunkOverlapTokens() {
        String longText = repeatTokens(500);
        int chunkSize = 100;
        int chunkOverlap = 25;
        var splitter = new OverlappingTokenTextSplitter(chunkSize, chunkOverlap);

        List<Document> chunks = splitter.apply(List.of(new Document(longText)));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);

        // For every adjacent chunk pair, the last `chunkOverlap` tokens of chunk[i]
        // must equal the first `chunkOverlap` tokens of chunk[i+1].
        for (int i = 0; i < chunks.size() - 1; i++) {
            var prev = encoding.encode(chunks.get(i).getText());
            var next = encoding.encode(chunks.get(i + 1).getText());
            // Prev's last `chunkOverlap` tokens (only meaningful if prev is full-size).
            if (prev.size() < chunkSize) continue;   // last chunk may be short
            for (int j = 0; j < chunkOverlap; j++) {
                int prevToken = prev.get(prev.size() - chunkOverlap + j);
                int nextToken = next.get(j);
                assertThat(nextToken)
                    .as("chunk[%d] tail token %d should equal chunk[%d] head token %d",
                        i, j, i + 1, j)
                    .isEqualTo(prevToken);
            }
        }
    }

    @Test
    void emptyOrBlankInputProducesNoChunks() {
        var splitter = new OverlappingTokenTextSplitter(512, 64);
        assertThat(splitter.apply(List.of(new Document(""))).stream()
            .allMatch(c -> c.getText() == null || c.getText().isBlank()))
            .isTrue();
    }

    @Test
    void preservesContentWhenConcatenatedWithoutOverlap() {
        String text = repeatTokens(300);
        var splitter = new OverlappingTokenTextSplitter(80, 0);
        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        // No overlap: content should round-trip token-wise.
        StringBuilder reconstructed = new StringBuilder();
        chunks.forEach(c -> reconstructed.append(c.getText()));

        int originalTokens = encoding.countTokens(text);
        int reconstructedTokens = encoding.countTokens(reconstructed.toString());
        assertThat(reconstructedTokens).isEqualTo(originalTokens);
    }

    /** Builds a string whose token count is approximately {@code targetTokens}. */
    private static String repeatTokens(int targetTokens) {
        StringBuilder sb = new StringBuilder();
        // "word " ≈ 1 token after BPE merge; pad until we hit the target.
        while (encoding.countTokens(sb.toString()) < targetTokens) {
            sb.append("word ");
        }
        return sb.toString();
    }
}
