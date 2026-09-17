package io.github.qwzhang01.agent.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Harness 3.2 (2026-09-17): visit metadata on trace entries —
 * {@code visitOrdinal} is assigned by {@link WorkflowState#record} (1-based,
 * append order), a pre-assigned ordinal (a recovered trace) is preserved
 * as-is, and the execution window {@code startedAt/endedAt} is carried on
 * the record without WorkflowState touching it.
 */
class StepRecordVisitMetadataTest {

    @Test
    @DisplayName("record assigns 1-based ordinals in append order")
    void recordAssignsOrdinalsInAppendOrder() {
        WorkflowState state = WorkflowState.of(null);
        state.record(StepRecord.success("node-a", 10L, 1, "ok"));
        state.record(StepRecord.success("node-b", 20L, 1, "ok"));
        state.record(StepRecord.failed("node-c", 30L, 2, "boom"));

        List<StepRecord> trace = state.getTrace();
        assertEquals(3, trace.size());
        assertEquals(1, trace.get(0).visitOrdinal());
        assertEquals(2, trace.get(1).visitOrdinal());
        assertEquals(3, trace.get(2).visitOrdinal());
    }

    @Test
    @DisplayName("a pre-assigned ordinal (recovered trace) is preserved, not renumbered")
    void recoveredOrdinalIsPreserved() {
        WorkflowState state = WorkflowState.of(null);
        // A recovery replay that already knows its ordinals: the store must
        // not renumber history — forging a fake "first visit" is impossible.
        state.record(new StepRecord("node-x", StepRecord.Status.SUCCESS,
                5L, 1, "recovered", 7, 100L, 200L));
        state.record(StepRecord.success("node-y", 6L, 1, "fresh"));

        List<StepRecord> trace = state.getTrace();
        assertEquals(7, trace.get(0).visitOrdinal(), "recovered entry keeps its original ordinal");
        assertEquals(2, trace.get(1).visitOrdinal(), "fresh entry continues the local sequence");
    }

    @Test
    @DisplayName("execution window round-trips on the record; legacy shape stays null")
    void executionWindowCarriedAndLegacyStaysNull() {
        StepRecord windowed = StepRecord.success("n", 10L, 1, "ok", 1_000L, 2_000L);
        assertEquals(1_000L, windowed.startedAt());
        assertEquals(2_000L, windowed.endedAt());

        // Legacy 5-field shape: window honestly absent, not faked.
        StepRecord legacy = StepRecord.success("n", 10L, 1, "ok");
        assertNull(legacy.startedAt());
        assertNull(legacy.endedAt());
        assertNull(legacy.visitOrdinal());
    }
}
