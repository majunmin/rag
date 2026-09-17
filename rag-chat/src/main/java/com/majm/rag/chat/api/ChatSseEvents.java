package com.majm.rag.chat.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.majm.rag.chat.application.ChatStream;
import com.majm.rag.common.api.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Wraps a token-producing {@code Flux<String>} into a structured SSE stream
 * that the admin UI / any other client can parse without guesswork.
 *
 * <p>Frames emitted:
 * <ul>
 *   <li>{@code event: context, data: <SearchResultItem JSON array>} — once, before tokens</li>
 *   <li>{@code event: token, data: <text-token>} — one per upstream onNext</li>
 *   <li>{@code event: done,  data: ""}            — terminal success</li>
 *   <li>{@code event: error, data: {ErrorResponse JSON}} — on upstream failure;
 *       includes a traceId so support can correlate to server logs</li>
 * </ul>
 *
 * <p>Errors that occur <i>before</i> the Flux is subscribed (e.g. conversation
 * not found, validation failure) still surface through GlobalExceptionHandler
 * as normal HTTP error responses; this class only fires for mid-stream
 * failures (LLM API rejects, network drops, timeouts).
 */
@Slf4j
final class ChatSseEvents {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private ChatSseEvents() {}

    static Flux<ServerSentEvent<String>> wrap(ChatStream stream) {
        Flux<ServerSentEvent<String>> context = Flux.defer(() -> Flux.just(
            ServerSentEvent.<String>builder()
                .event("context")
                .data(serializeContext(stream))
                .build()));
        Flux<ServerSentEvent<String>> tokens = stream.tokens()
            .map(token -> ServerSentEvent.<String>builder()
                .event("token")
                .data(token)
                .build());
        Flux<ServerSentEvent<String>> done = Flux.just(ServerSentEvent.<String>builder()
                .event("done")
                .data("")
                .build());
        return Flux.concat(context, tokens, done)
            .onErrorResume(ChatSseEvents::errorFrame);
    }

    private static String serializeContext(ChatStream stream) {
        try {
            return MAPPER.writeValueAsString(stream.context());
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize retrieval context", e);
        }
    }

    private static Flux<ServerSentEvent<String>> errorFrame(Throwable t) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] SSE stream failed", traceId, t);
        ErrorResponse body = ErrorResponse.of(
            "STREAM_ERROR",
            "Streaming response interrupted",
            traceId);
        return Flux.just(ServerSentEvent.<String>builder()
            .event("error")
            .data(serialize(body, traceId))
            .build());
    }

    private static String serialize(ErrorResponse body, String fallbackTraceId) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JacksonException e) {
            // Defensive: this should never happen on a record with primitive
            // fields, but if it does, give the client enough to act on.
            return "{\"code\":\"STREAM_ERROR\",\"message\":\"Streaming response interrupted\","
                + "\"traceId\":\"" + fallbackTraceId + "\"}";
        }
    }
}
