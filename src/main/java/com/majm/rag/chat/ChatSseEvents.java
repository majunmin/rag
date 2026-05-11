package com.majm.rag.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.majm.rag.common.dto.ErrorResponse;
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

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private ChatSseEvents() {}

    static Flux<ServerSentEvent<String>> wrap(Flux<String> tokens) {
        return tokens
            .map(token -> ServerSentEvent.<String>builder()
                .event("token")
                .data(token)
                .build())
            .concatWith(Flux.just(ServerSentEvent.<String>builder()
                .event("done")
                .data("")
                .build()))
            .onErrorResume(ChatSseEvents::errorFrame);
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
        } catch (JsonProcessingException e) {
            // Defensive: this should never happen on a record with primitive
            // fields, but if it does, give the client enough to act on.
            return "{\"code\":\"STREAM_ERROR\",\"message\":\"Streaming response interrupted\","
                + "\"traceId\":\"" + fallbackTraceId + "\"}";
        }
    }
}
