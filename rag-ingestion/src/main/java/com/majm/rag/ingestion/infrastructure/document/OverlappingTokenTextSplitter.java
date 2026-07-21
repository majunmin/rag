package com.majm.rag.ingestion.infrastructure.document;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-based text splitter with sliding-window overlap.
 *
 * <p>Spring AI's {@code TokenTextSplitter} does not support overlap — neighboring
 * chunks share no content, so a sentence cut across a chunk boundary loses
 * context for retrieval. This splitter slides a window of {@code chunkSize}
 * tokens forward by {@code chunkSize - chunkOverlap} each step, so each chunk
 * shares its first {@code chunkOverlap} tokens with the previous chunk's tail.
 *
 * <p>Tokenization uses jtokkit's {@code cl100k_base} encoding (same encoder
 * Spring AI's TokenTextSplitter uses).
 *
 * <p>Splitting is purely token-based; punctuation-aware boundary selection is
 * intentionally dropped. RAG retrieval quality is dominated by overlap, not by
 * sentence-perfect chunk endings.
 */
public class OverlappingTokenTextSplitter extends TextSplitter {

    private final Encoding encoding;
    private final int chunkSize;
    private final int chunkOverlap;

    public OverlappingTokenTextSplitter(int chunkSize, int chunkOverlap) {
        Validate.isTrue(chunkSize > 0, "chunkSize must be > 0, got %d", chunkSize);
        Validate.isTrue(chunkOverlap >= 0, "chunkOverlap must be >= 0, got %d", chunkOverlap);
        Validate.isTrue(chunkOverlap < chunkSize,
            "chunkOverlap (%d) must be < chunkSize (%d)", chunkOverlap, chunkSize);
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    protected List<String> splitText(String text) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        IntArrayList tokens = encoding.encode(text);
        int total = tokens.size();
        if (total <= chunkSize) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int step = chunkSize - chunkOverlap;
        for (int start = 0; start < total; start += step) {
            int end = Math.min(start + chunkSize, total);
            chunks.add(encoding.decode(slice(tokens, start, end)));
            if (end == total) {
                break;
            }
        }
        return chunks;
    }

    private static IntArrayList slice(IntArrayList src, int from, int to) {
        IntArrayList out = new IntArrayList(to - from);
        for (int i = from; i < to; i++) {
            out.add(src.get(i));
        }
        return out;
    }
}
