package com.majm.rag.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSseEventsTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void emitsTokenEventsThenDone_onNormalCompletion() {
        Flux<ServerSentEvent<String>> out = ChatSseEvents.wrap(Flux.just("hello", " ", "world"));

        StepVerifier.create(out)
            .assertNext(e -> {
                assertThat(e.event()).isEqualTo("token");
                assertThat(e.data()).isEqualTo("hello");
            })
            .assertNext(e -> assertThat(e.data()).isEqualTo(" "))
            .assertNext(e -> assertThat(e.data()).isEqualTo("world"))
            .assertNext(e -> {
                assertThat(e.event()).isEqualTo("done");
                assertThat(e.data()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void emitsErrorEvent_onUpstreamFailure() {
        // Upstream emits some tokens then errors out.
        Flux<String> faulty = Flux.concat(
            Flux.just("partial"),
            Flux.error(new RuntimeException("LLM timed out")));

        List<ServerSentEvent<String>> events = ChatSseEvents.wrap(faulty)
            .collectList()
            .block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).event()).isEqualTo("token");
        assertThat(events.get(0).data()).isEqualTo("partial");

        ServerSentEvent<String> errEvt = events.get(1);
        assertThat(errEvt.event()).isEqualTo("error");
        assertThat(errEvt.data()).isNotBlank();

        // Error body is JSON-serialised ErrorResponse with traceId.
        ParsedError parsed = parse(errEvt.data());
        assertThat(parsed.code).isEqualTo("STREAM_ERROR");
        assertThat(parsed.traceId).hasSize(8);   // first 8 chars of a UUID
        assertThat(parsed.message).isNotBlank();
    }

    @Test
    void errorAtTheVeryStart_stillEmitsErrorEvent() {
        Flux<String> immediatelyFailing = Flux.error(new IllegalStateException("upstream down"));

        StepVerifier.create(ChatSseEvents.wrap(immediatelyFailing))
            .assertNext(e -> {
                assertThat(e.event()).isEqualTo("error");
                ParsedError parsed = parse(e.data());
                assertThat(parsed.code).isEqualTo("STREAM_ERROR");
            })
            .verifyComplete();
    }

    @Test
    void emptyUpstream_emitsOnlyDone() {
        StepVerifier.create(ChatSseEvents.wrap(Flux.empty()))
            .assertNext(e -> assertThat(e.event()).isEqualTo("done"))
            .verifyComplete();
    }

    private ParsedError parse(String json) {
        try {
            return mapper.readValue(json, ParsedError.class);
        } catch (Exception e) {
            throw new AssertionError("Bad JSON: " + json, e);
        }
    }

    /** Subset of ErrorResponse for assertion convenience. */
    record ParsedError(String code, String message, String traceId) {}
}
