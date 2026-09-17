package io.github.qwzhang01.agent.core.approval;

import io.github.qwzhang01.agent.core.event.BoundaryEvent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Boundary-telemetry decorator over {@link ApprovalStore} (harness 4.4):
 * every decision and every refused decision attempt emits an
 * {@link BoundaryEvent.ApprovalBoundaryEvent}. Side channel by contract —
 * a throwing sink is swallowed, the store semantics are identical.
 * <p>
 * Wrapping the store (rather than editing each implementation) keeps the
 * event wiring in one place: JDBC, in-memory and future Redis backends all
 * get the telemetry for free, and a deployment that wants none of it just
 * does not wrap.
 * <p>
 * What the events carry: the durable approvalId, the run, the decision
 * word, the decider identity. Never the request payload — the audit ledger
 * owns content, telemetry owns structure.
 */
public final class ObservingApprovalStore implements ApprovalStore {

    private final ApprovalStore delegate;
    private final Consumer<BoundaryEvent> eventSink;

    public ObservingApprovalStore(ApprovalStore delegate, Consumer<BoundaryEvent> eventSink) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.eventSink = eventSink != null ? eventSink : e -> { };
    }

    /** Side-channel emission: telemetry must never break the store. */
    private void emit(BoundaryEvent event) {
        try {
            eventSink.accept(event);
        } catch (RuntimeException e) {
            // counted nowhere visible beyond stderr — side channel rule
            System.err.println("[ObservingApprovalStore] event sink failed (swallowed): " + e);
        }
    }

    @Override
    public ApprovalRequest submit(ApprovalRequest request) {
        return delegate.submit(request);
    }

    @Override
    public Optional<ApprovalRequest> get(String approvalId) {
        return delegate.get(approvalId);
    }

    @Override
    public ApprovalRequest decide(ApprovalRequest request, ApprovalDecision decision,
                                   ApprovalStatus targetStatus) {
        try {
            ApprovalRequest decided = delegate.decide(request, decision, targetStatus);
            emit(new BoundaryEvent.ApprovalDecided(
                    decided.approvalId(), decided.runId(), targetStatus.name(),
                    decision != null ? decision.decidedBy() : null, Instant.now()));
            return decided;
        } catch (ApprovalConflictException e) {
            emit(new BoundaryEvent.ApprovalRefused(request.approvalId(), e.getMessage(), Instant.now()));
            throw e;
        }
    }

    @Override
    public ApprovalRequest revoke(String approvalId, ApprovalDecision revocation) {
        try {
            ApprovalRequest revoked = delegate.revoke(approvalId, revocation);
            emit(new BoundaryEvent.ApprovalDecided(
                    revoked.approvalId(), revoked.runId(), "REVOKED",
                    revocation != null ? revocation.decidedBy() : null, Instant.now()));
            return revoked;
        } catch (ApprovalConflictException e) {
            emit(new BoundaryEvent.ApprovalRefused(approvalId, e.getMessage(), Instant.now()));
            throw e;
        }
    }

    @Override
    public List<String> expireOverdue(long nowEpochMs) {
        return delegate.expireOverdue(nowEpochMs);
    }

    @Override
    public List<ApprovalRequest> pendingForRun(String runId) {
        return delegate.pendingForRun(runId);
    }

    @Override
    public List<ApprovalRequest> allPending() {
        return delegate.allPending();
    }
}
