package io.github.qwzhang01.agent.core.client;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  pins the three resilience knobs the roadmap names —
 * Retry-After honoring, circuit breaking, credential rotation — as ONE
 * decorator. Each test scripts a failure sequence into a fake client and
 * asserts the observable outcome (call count, thrown type, breaker state).
 */
class ResilientModelClientTest {

    private static ModelRequest anyRequest() {
        return ModelRequest.builder().build();
    }


    @Test
    void retryAfter_hintIsHonoredOverExponentialGuess() {
        ScriptedMock mock = new ScriptedMock();
        mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.RATE_LIMITED,
                "slow down", null, Duration.ofMillis(50), 429, "test"));
        mock.script(ModelResponse.text("ok"));

        long start = System.nanoTime();
        ModelResponse resp = new ResilientModelClient(mock, 3, Duration.ofSeconds(10))
                .chat(anyRequest());
        long waitedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("ok", resp.content());
        assertEquals(2, mock.calls.get());
        // Exponential guess would be 10s; hint says 50ms. Assert we waited
        // the hint (not the guess) and not much more than the hint.
        assertTrue(waitedMs >= 45, "should honor the 50ms hint, waited " + waitedMs + "ms");
        assertTrue(waitedMs < 5_000, "must not wait the 10s exponential guess");
    }

    @Test
    void retryAfter_capBoundsTheHint() {
        ScriptedMock mock = new ScriptedMock();
        mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.RATE_LIMITED,
                "slow down", null, Duration.ofSeconds(120), 429, "test"));
        mock.script(ModelResponse.text("ok"));

        long start = System.nanoTime();
        ModelResponse resp = new ResilientModelClient(mock, 3, Duration.ofMillis(10),
                Duration.ofMillis(80), null, Integer.MAX_VALUE, Duration.ZERO)
                .chat(anyRequest());
        long waitedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("ok", resp.content());
        // Hint said 120s, cap says 80ms — the cap wins.
        assertTrue(waitedMs < 5_000, "cap must bound the 120s hint, waited " + waitedMs + "ms");
    }


    @Test
    void breaker_opensAfterConsecutiveFailuresAndFailsFast() {
        ScriptedMock mock = new ScriptedMock();
        for (int i = 0; i < 3; i++) {
            mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.MODEL_ERROR,
                    "provider 5xx", null, null, 500, "test"));
        }
        // Threshold 3: attempts = 1 initial + 2 retries, all fail ->
        // consecutiveFailures = 3 >= threshold -> OPEN.
        ResilientModelClient client = new ResilientModelClient(mock, 2, Duration.ofMillis(1),
                null, null, 3, Duration.ofSeconds(30));

        assertThrows(ProviderCallException.class, () -> client.chat(anyRequest()));
        assertEquals(3, mock.calls.get());
        assertEquals(ResilientModelClient.BreakerState.OPEN, client.getBreakerState());

        // Breaker OPEN: the next call fails fast WITHOUT touching the delegate.
        int callsBefore = mock.calls.get();
        ProviderCallException fast = assertThrows(ProviderCallException.class,
                () -> client.chat(anyRequest()));
        assertEquals(callsBefore, mock.calls.get(), "breaker must fail fast, no delegate call");
        assertTrue(fast.getMessage().contains("circuit open"),
                "fail-fast exception should say why, got: " + fast.getMessage());
    }

    @Test
    void breaker_probesAfterOpenDurationAndClosesOnSuccess() {
        ScriptedMock mock = new ScriptedMock();
        for (int i = 0; i < 3; i++) {
            mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.MODEL_ERROR,
                    "provider 5xx", null, null, 500, "test"));
        }
        mock.script(ModelResponse.text("recovered"));

        ResilientModelClient client = new ResilientModelClient(mock, 2, Duration.ofMillis(1),
                null, null, 3, Duration.ofMillis(50));

        assertThrows(ProviderCallException.class, () -> client.chat(anyRequest()));
        assertEquals(ResilientModelClient.BreakerState.OPEN, client.getBreakerState());

        // Wait past openDuration -> the next call is the HALF_OPEN probe.
        awaitMillis(70);
        ModelResponse resp = client.chat(anyRequest());
        assertEquals("recovered", resp.content());
        assertEquals(ResilientModelClient.BreakerState.CLOSED, client.getBreakerState());
        assertEquals(0, client.getConsecutiveFailures());
    }

    @Test
    void breaker_successResetsConsecutiveFailureCount() {
        ScriptedMock mock = new ScriptedMock();
        mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.MODEL_ERROR,
                "once", null, null, 500, "test"));
        mock.script(ModelResponse.text("ok1"));
        mock.script(ModelResponse.text("ok2"));

        // Threshold 2: one failure then two successes must never open.
        ResilientModelClient client = new ResilientModelClient(mock, 1, Duration.ofMillis(1),
                null, null, 2, Duration.ofSeconds(30));

        assertEquals("ok1", client.chat(anyRequest()).content()); // fail, retry, succeed
        assertEquals("ok2", client.chat(anyRequest()).content()); // clean success
        assertEquals(0, client.getConsecutiveFailures());
        assertEquals(ResilientModelClient.BreakerState.CLOSED, client.getBreakerState());
    }

    @Test
    void breaker_callerSideErrorsDoNotOpenIt() {
        ScriptedMock mock = new ScriptedMock();
        for (int i = 0; i < 5; i++) {
            mock.script(new ProviderCallException(
                    ProviderCallException.ProviderErrorCode.INVALID_REQUEST, "bad payload"));
        }

        // Threshold 1 would open on ANY counted failure; INVALID_REQUEST
        // must not count. Non-retryable -> thrown on first attempt.
        ResilientModelClient client = new ResilientModelClient(mock, 2, Duration.ofMillis(1),
                null, null, 1, Duration.ofSeconds(30));

        for (int i = 0; i < 5; i++) {
            assertThrows(ProviderCallException.class, () -> client.chat(anyRequest()));
        }
        assertEquals(ResilientModelClient.BreakerState.CLOSED, client.getBreakerState());
        assertEquals(0, client.getConsecutiveFailures());
    }


    @Test
    void rotation_authErrorRotatesAndRecovers() {
        ScriptedMock mock = new ScriptedMock();
        mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.AUTH_ERROR,
                "key expired", null, null, 401, "test"));
        mock.script(ModelResponse.text("ok-after-rotation"));

        KeyPool pool = new KeyPool("key-b");
        ResilientModelClient client = new ResilientModelClient(mock, 2, Duration.ofMillis(1),
                null, pool, Integer.MAX_VALUE, Duration.ZERO);

        ModelResponse resp = client.chat(anyRequest());
        assertEquals("ok-after-rotation", resp.content());
        assertEquals(2, mock.calls.get());
        assertEquals(1, pool.applied.get(), "rotation must apply the new key exactly once");
    }

    @Test
    void rotation_exhaustedPoolPropagatesAuthError() {
        ScriptedMock mock = new ScriptedMock();
        mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.AUTH_ERROR,
                "key expired", null, null, 401, "test"));

        KeyPool pool = new KeyPool(); // empty pool: nextCredential() = null
        ResilientModelClient client = new ResilientModelClient(mock, 2, Duration.ofMillis(1),
                null, pool, Integer.MAX_VALUE, Duration.ZERO);

        ProviderCallException ex = assertThrows(ProviderCallException.class,
                () -> client.chat(anyRequest()));
        assertEquals(ProviderCallException.ProviderErrorCode.AUTH_ERROR, ex.getProviderCode());
        assertEquals(1, mock.calls.get());
        assertEquals(0, pool.applied.get());
    }


    @Test
    void legacy_legacyModelExceptionIsUpgradedNotSwallowed() {
        ScriptedMock mock = new ScriptedMock();
        mock.script(new ModelException(ModelException.ErrorCode.NETWORK_ERROR, "legacy net fail"));
        mock.script(ModelResponse.text("ok"));

        ResilientModelClient client = new ResilientModelClient(mock, 2, Duration.ofMillis(1));

        ModelResponse resp = client.chat(anyRequest());
        assertEquals("ok", resp.content());
        assertEquals(2, mock.calls.get());
    }

    @Test
    void legacy_canceledNeverRetried() {
        ScriptedMock mock = new ScriptedMock();
        mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.CANCELED,
                "caller aborted"));

        ResilientModelClient client = new ResilientModelClient(mock, 5, Duration.ofMillis(1));

        ProviderCallException ex = assertThrows(ProviderCallException.class,
                () -> client.chat(anyRequest()));
        assertEquals(ProviderCallException.ProviderErrorCode.CANCELED, ex.getProviderCode());
        assertEquals(1, mock.calls.get(), "CANCELED is never retried");
    }

    @Test
    void stream_openFailuresParticipateInBreaker() {
        ScriptedMock mock = new ScriptedMock();
        for (int i = 0; i < 2; i++) {
            mock.script(new ProviderCallException(ProviderCallException.ProviderErrorCode.MODEL_ERROR,
                    "provider down", null, null, 500, "test"));
        }
        mock.script(Stream.of(new StreamEvent.Done(ModelResponse.text("done"))));

        ResilientModelClient client = new ResilientModelClient(mock, 1, Duration.ofMillis(1),
                null, null, 2, Duration.ofSeconds(30));

        assertThrows(ProviderCallException.class,
                () -> client.stream(anyRequest()).forEach(e -> { }));
        assertEquals(2, mock.calls.get());
        assertEquals(ResilientModelClient.BreakerState.OPEN, client.getBreakerState());

        // Breaker open: stream open fails fast too.
        int before = mock.calls.get();
        assertThrows(ProviderCallException.class,
                () -> client.stream(anyRequest()).forEach(e -> { }));
        assertEquals(before, mock.calls.get());
    }

    private static void awaitMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Scripts call outcomes in order: each {@code chat}/{@code stream} pops
     * the next item — a ModelResponse to return, a Throwable to throw, or a
     * Stream to hand back (stream open only).
     */
    static final class ScriptedMock implements ModelClient {
        final AtomicInteger calls = new AtomicInteger();
        private final Queue<Object> script = new ArrayDeque<>();

        void script(Object outcome) {
            script.add(outcome);
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            calls.incrementAndGet();
            Object next = script.poll();
            if (next instanceof ModelResponse resp) {
                return resp;
            }
            if (next instanceof RuntimeException rte) {
                throw rte;
            }
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR, "script exhausted");
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            calls.incrementAndGet();
            Object next = script.poll();
            if (next instanceof Stream<?> s) {
                @SuppressWarnings("unchecked")
                Stream<StreamEvent> events = (Stream<StreamEvent>) s;
                return events;
            }
            if (next instanceof RuntimeException rte) {
                throw rte;
            }
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR, "script exhausted");
        }
    }

    /** Rotates through a finite key pool, counting applies. */
    static final class KeyPool implements ResilientModelClient.CredentialRotation {
        private final Queue<String> keys;
        final AtomicInteger applied = new AtomicInteger();

        KeyPool(String... keys) {
            this.keys = new ArrayDeque<>(java.util.Arrays.asList(keys));
        }

        @Override
        public String nextCredential() {
            return keys.poll();
        }

        @Override
        public void apply(String credential) {
            applied.incrementAndGet();
        }
    }
}
