package com.majm.rag.retrieval.rewrite;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryRewriteServiceTest {

    @Test
    void disabled_returnsPassthroughWithoutRunningChain() {
        AtomicInteger calls = new AtomicInteger();
        var counting = countingRewriter("hyde", calls,
            q -> new RewriteResult(q, "HYDE:" + q, List.of()));

        var svc = new QueryRewriteService(List.of(counting), false, "hyde", 3000);
        svc.resolveChain();

        RewriteResult out = svc.rewrite("hello", List.of());

        assertThat(out.embeddingQuery()).isEqualTo("hello");
        assertThat(out.originalQuery()).isEqualTo("hello");
        assertThat(calls.get()).isZero();
    }

    @Test
    void emptyStrategiesConfig_fallsBackToPassthrough() {
        var svc = new QueryRewriteService(List.of(), true, "", 3000);
        svc.resolveChain();

        RewriteResult out = svc.rewrite("hello", List.of());

        assertThat(out.embeddingQuery()).isEqualTo("hello");
    }

    @Test
    void unknownStrategyName_isLoggedAndSkipped() {
        AtomicInteger calls = new AtomicInteger();
        var hyde = countingRewriter("hyde", calls,
            q -> new RewriteResult(q, "HYDE:" + q, List.of()));

        var svc = new QueryRewriteService(List.of(hyde), true, "does-not-exist,hyde", 3000);
        svc.resolveChain();

        RewriteResult out = svc.rewrite("q", List.of());

        assertThat(out.embeddingQuery()).isEqualTo("HYDE:q");
        assertThat(calls.get()).isOne();
    }

    @Test
    void chainComposes_conversationalThenHyde() {
        var conversational = staticRewriter("conversational",
            q -> q.equals("follow-up")
                ? new RewriteResult("standalone", "standalone", List.of())
                : RewriteResult.passthrough(q));
        var hyde = staticRewriter("hyde",
            q -> new RewriteResult(q, "HYDE:" + q, List.of()));

        var svc = new QueryRewriteService(List.of(conversational, hyde), true,
            "conversational,hyde", 3000);
        svc.resolveChain();

        RewriteResult out = svc.rewrite("follow-up",
            List.of(Map.of("role", "user", "content", "prior")));

        // Conversational rewrites to "standalone"; HyDE sees that and embeds it.
        assertThat(out.originalQuery()).isEqualTo("standalone");
        assertThat(out.embeddingQuery()).isEqualTo("HYDE:standalone");
    }

    @Test
    void chainComposes_conversationalThenMultiQuery() {
        var conversational = staticRewriter("conversational",
            q -> new RewriteResult("standalone Q", "standalone Q", List.of()));
        var multi = staticRewriter("multi-query",
            q -> new RewriteResult(q, q, List.of("rephrase A", "rephrase B")));

        var svc = new QueryRewriteService(List.of(conversational, multi), true,
            "conversational,multi-query", 3000);
        svc.resolveChain();

        RewriteResult out = svc.rewrite("any",
            List.of(Map.of("role", "user", "content", "earlier")));

        assertThat(out.originalQuery()).isEqualTo("standalone Q");
        assertThat(out.embeddingQuery()).isEqualTo("standalone Q");
        assertThat(out.expandedQueries()).containsExactly("rephrase A", "rephrase B");
    }

    @Test
    void timeout_returnsPassthrough() {
        var slow = staticRewriter("hyde", q -> {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new RewriteResult(q, "HYDE:" + q, List.of());
        });

        // Service has 500ms minimum budget; we pass below it but it clamps to 500.
        var svc = new QueryRewriteService(List.of(slow), true, "hyde", 200);
        svc.resolveChain();

        RewriteResult out = svc.rewrite("q", List.of());

        assertThat(out.embeddingQuery()).isEqualTo("q");
    }

    @Test
    void strategyExceptionInsideChain_doesntKillResult() {
        var failing = staticRewriter("hyde", q -> { throw new RuntimeException("boom"); });

        var svc = new QueryRewriteService(List.of(failing), true, "hyde", 3000);
        svc.resolveChain();

        RewriteResult out = svc.rewrite("q", List.of());

        assertThat(out.embeddingQuery()).isEqualTo("q");
    }

    @Test
    void blankQuery_throwsIllegalArgument() {
        var svc = new QueryRewriteService(List.of(), true, "", 3000);
        svc.resolveChain();

        assertThatThrownBy(() -> svc.rewrite("", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullHistory_isTolerated() {
        var conversational = staticRewriter("conversational", q -> RewriteResult.passthrough(q));
        var svc = new QueryRewriteService(List.of(conversational), true, "conversational", 3000);
        svc.resolveChain();

        RewriteResult out = svc.rewrite("q", null);

        assertThat(out.embeddingQuery()).isEqualTo("q");
    }

    @Test
    void timeoutBudget_isClampedToMinimum() {
        // 50ms config — service should clamp up to 500ms internal floor.
        var svc = new QueryRewriteService(List.of(), true, "", 50);
        long min = (long) ReflectionTestUtils.getField(svc, "timeoutMs");
        assertThat(min).isEqualTo(500L);
    }

    // ---------- helpers ----------

    private static QueryRewriter staticRewriter(String name,
                                                 java.util.function.Function<String, RewriteResult> impl) {
        return new QueryRewriter() {
            @Override public RewriteResult rewrite(String query, List<Map<String, String>> history) {
                return impl.apply(query);
            }
            @Override public String name() { return name; }
        };
    }

    private static QueryRewriter countingRewriter(String name, AtomicInteger counter,
                                                   java.util.function.Function<String, RewriteResult> impl) {
        return new QueryRewriter() {
            @Override public RewriteResult rewrite(String query, List<Map<String, String>> history) {
                counter.incrementAndGet();
                return impl.apply(query);
            }
            @Override public String name() { return name; }
        };
    }
}
