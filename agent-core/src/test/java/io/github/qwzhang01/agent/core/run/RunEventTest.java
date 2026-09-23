package io.github.qwzhang01.agent.core.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * acceptance: lifecycle events carry correlation + version.
 */
class RunEventTest {

    @Test
    void everyEventCarriesRunTraceAndVersion() {
        Instant now = Instant.now();
        RunEvent started = new RunEvent.RunStarted("r1", "tr1", now);
        RunEvent step = new RunEvent.StepStarted("r1", "tr1", "node-a", 3, 2, now);
        RunEvent completed = new RunEvent.StepCompleted("r1", "tr1", "node-a", 3, 2,
                120, "ok", null, now);
        RunEvent failed = new RunEvent.RunFailed("r1", "tr1", FailureKind.TIMEOUT,
                "deadline", now);

        for (RunEvent e : new RunEvent[]{started, step, completed, failed}) {
            assertEquals("r1", e.runId());
            assertEquals("tr1", e.traceId());
            assertEquals(1, e.schemaVersion());
            assertEquals(now, e.occurredAt());
        }
    }

    @Test
    void cancelStepIsNotToolFailure() {
        RunEvent.StepCompleted cancelledStep = new RunEvent.StepCompleted(
                "r1", "tr1", "n", 1, 1, 5, "cancelled", FailureKind.CANCELLED, Instant.now());
        assertNull(new RunEvent.StepCompleted("r1", "tr1", "n", 1, 1, 5, "ok", null,
                Instant.now()).failureKind());
        assertEquals(FailureKind.CANCELLED, cancelledStep.failureKind());
        assertNotEquals(FailureKind.TOOL_FAILURE, cancelledStep.failureKind());
    }
}
