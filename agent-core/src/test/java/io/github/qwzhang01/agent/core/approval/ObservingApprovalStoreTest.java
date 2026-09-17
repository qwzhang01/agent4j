package io.github.qwzhang01.agent.core.approval;

import io.github.qwzhang01.agent.core.event.BoundaryEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 4.4 (2026-09-17): the approval boundary gets telemetry twins.
 * {@link ObservingApprovalStore} decorates any backend (JDBC, in-memory,
 * future Redis) so decide/revoke emit {@code ApprovalDecided} /
 * {@code ApprovalRefused} facts without the backends repeating the wiring.
 */
class ObservingApprovalStoreTest {

    /** A submitting helper: puts one PENDING request in the store. */
    private ApprovalRequest seed(ApprovalStore store) {
        ApprovalRequest req = new ApprovalRequest(
                ApprovalRequest.idForNode("run-1", "step-1"),
                "run-1", "step-1", "tool-call-hash",
                "agent", "HIGH", "refund node", 0, System.currentTimeMillis(),
                ApprovalStatus.PENDING, null, 0);
        return store.submit(req);
    }

    @Test
    @DisplayName("decide success emits ApprovalDecided with structure only")
    void decideSuccessEmitsDecided() {
        InMemoryApprovalStore backend = new InMemoryApprovalStore();
        List<BoundaryEvent> events = new ArrayList<>();
        ObservingApprovalStore store = new ObservingApprovalStore(backend, events::add);

        ApprovalRequest req = seed(backend);
        store.decide(req, ApprovalDecision.of("boss", "looks safe", 0),
                ApprovalStatus.APPROVED);

        assertEquals(1, events.size());
        BoundaryEvent.ApprovalDecided e = (BoundaryEvent.ApprovalDecided) events.get(0);
        assertEquals(req.approvalId(), e.approvalId());
        assertEquals("run-1", e.runId());
        assertEquals("APPROVED", e.decision());
        assertEquals("boss", e.decidedBy());
    }

    @Test
    @DisplayName("decide conflict emits ApprovalRefused then rethrows: telemetry never swallows the store's truth")
    void decideConflictEmitsRefusedAndRethrows() {
        InMemoryApprovalStore backend = new InMemoryApprovalStore();
        List<BoundaryEvent> events = new ArrayList<>();
        ObservingApprovalStore store = new ObservingApprovalStore(backend, events::add);

        ApprovalRequest req = seed(backend);
        store.decide(req, ApprovalDecision.of("boss", "first", 0),
                ApprovalStatus.APPROVED);

        // Stale version (0 against a request already at 1): conflict.
        assertThrows(ApprovalConflictException.class, () -> store.decide(req,
                ApprovalDecision.of("boss", "double decide", 0),
                ApprovalStatus.APPROVED));

        assertEquals(2, events.size());
        assertTrue(events.get(1) instanceof BoundaryEvent.ApprovalRefused,
                "conflict emits the refused twin");
        assertEquals(req.approvalId(),
                ((BoundaryEvent.ApprovalRefused) events.get(1)).approvalId());
    }

    @Test
    @DisplayName("revoke emits ApprovalDecided with REVOKED; unknown id refuses")
    void revokeEmitsRevokedThenRefusesUnknown() {
        InMemoryApprovalStore backend = new InMemoryApprovalStore();
        List<BoundaryEvent> events = new ArrayList<>();
        ObservingApprovalStore store = new ObservingApprovalStore(backend, events::add);

        ApprovalRequest req = seed(backend);
        store.decide(req, ApprovalDecision.of("boss", "ok", 0), ApprovalStatus.APPROVED);
        store.revoke(req.approvalId(), ApprovalDecision.of("admin", "withdraw", 1));

        assertEquals(2, events.size());
        assertEquals("REVOKED",
                ((BoundaryEvent.ApprovalDecided) events.get(1)).decision());

        assertThrows(ApprovalConflictException.class,
                () -> store.revoke("ghost-id", ApprovalDecision.of("admin", "x", 0)));
        assertTrue(events.get(2) instanceof BoundaryEvent.ApprovalRefused);
    }

    @Test
    @DisplayName("throwing sink is swallowed: the decision is the boundary's truth, telemetry is a side channel")
    void throwingSinkNeverBreaksTheDecision() {
        InMemoryApprovalStore backend = new InMemoryApprovalStore();
        ObservingApprovalStore store = new ObservingApprovalStore(
                backend, e -> { throw new IllegalStateException("sink broken"); });

        ApprovalRequest req = seed(backend);
        store.decide(req, ApprovalDecision.of("boss", "ok", 0), ApprovalStatus.APPROVED);

        assertEquals(ApprovalStatus.APPROVED,
                store.get(req.approvalId()).orElseThrow().status(),
                "decision landed even though the sink threw");
    }
}
