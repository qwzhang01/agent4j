package io.github.qwzhang01.agent.core.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * acceptance: the immutable RunContext itself.
 */
class RunContextTest {

    @Test
    void systemGeneratesRunAndTraceIds() {
        RunContext ctx = RunContext.create();
        assertNotNull(ctx.runId());
        assertNotNull(ctx.traceId());
        assertNotEquals(ctx.runId(), ctx.traceId());
    }

    @Test
    void customRunIdIsHonoredAtEntryOnly() {
        RunContext ctx = RunContext.builder().runId("run-42").build();
        assertEquals("run-42", ctx.runId());
    }

    @Test
    void deriveChildKeepsTraceAndParentButOwnRunId() {
        RunContext parent = RunContext.builder()
                .tenantId("t1").userId("u1").agentId("outer")
                .capabilities(Set.of("read"))
                .build();
        RunContext child = parent.deriveChild("inner");

        assertEquals(parent.runId(), child.parentRunId());
        assertEquals(parent.traceId(), child.traceId());
        assertEquals("inner", child.agentId());
        assertNotEquals(parent.runId(), child.runId());
        assertEquals("t1", child.tenantId());
        assertEquals("u1", child.userId());
        assertEquals(Set.of("read"), child.capabilities());
    }

    @Test
    void deadlineMath() {
        RunContext noDeadline = RunContext.create();
        assertFalse(noDeadline.isDeadlineExceeded());
        assertEquals(-1, noDeadline.deadlineRemainingMs());

        RunContext past = RunContext.builder()
                .deadline(Instant.now().minusSeconds(1))
                .build();
        assertTrue(past.isDeadlineExceeded());
        assertEquals(0, past.deadlineRemainingMs());

        RunContext future = RunContext.builder()
                .deadline(Instant.now().plusSeconds(60))
                .build();
        assertFalse(future.isDeadlineExceeded());
        assertTrue(future.deadlineRemainingMs() > 50_000);
    }

    @Test
    void checkAliveThrowsStructuredSignals() {
        CancellationSource source = new CancellationSource();
        RunContext ctx = RunContext.builder()
                .cancellationToken(source.token())
                .build();
        assertDoesNotThrow(ctx::checkAlive);

        source.cancel();
        assertThrows(RunCancelledException.class, ctx::checkAlive);

        RunContext expired = RunContext.builder()
                .deadline(Instant.now().minusSeconds(1))
                .build();
        assertThrows(RunDeadlineException.class, expired::checkAlive);
    }

    @Test
    void toStringRedactsUserIdAndToken() {
        RunContext ctx = RunContext.builder()
                .tenantId("t1").userId("secret-user@mail")
                .cancellationToken(new CancellationSource().token())
                .build();
        String s = ctx.toString();
        String log = ctx.toLogString();
        assertFalse(s.contains("secret-user"), "toString must redact userId");
        assertFalse(log.contains("secret-user"), "toLogString must redact userId");
        assertTrue(log.contains(ctx.runId()));
        assertTrue(log.contains(ctx.traceId()));
    }

    @Test
    void cancellationSourceCancelIsIdempotentAndFirstWins() {
        CancellationSource source = new CancellationSource();
        assertTrue(source.cancel());
        assertFalse(source.cancel(), "second cancel must not win");
        assertTrue(source.token().isCancelled());
    }
}
