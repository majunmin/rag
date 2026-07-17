package com.majm.rag.chat;

import com.majm.rag.knowledge.dto.SearchResultItem;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

public record ChatStream(List<SearchResultItem> context, Flux<String> tokens) {

    public ChatStream {
        context = context == null ? List.of() : List.copyOf(context);
        Objects.requireNonNull(tokens, "tokens is required");
    }
}
