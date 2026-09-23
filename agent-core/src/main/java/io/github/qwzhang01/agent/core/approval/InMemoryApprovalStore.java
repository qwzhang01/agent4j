package io.github.qwzhang01.agent.core.approval;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reference in-memory {@link ApprovalStore} .
 * <p>
 * Semantics identical to the future production backend — that is the whole
 * test strategy: prove the protocol here, port the SQL later. Not durable
 * across restarts by itself; a file-backed variant composes it for the
 * teaching v1.
 */
public final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalRequest> byId = new ConcurrentHashMap<>();
    private final Map<String, List<ApprovalRequest>> byRun = new ConcurrentHashMap<>();

    @Override
    public ApprovalRequest submit(ApprovalRequest request) {
        ApprovalRequest existing = byId.putIfAbsent(request.approvalId(), request);
        if (existing != null) {
            return existing; // idempotent: original wins, no duplicate row
        }
        byRun.computeIfAbsent(request.runId(), k -> new CopyOnWriteArrayList<>()).add(request);
        return request;
    }

    @Override
    public Optional<ApprovalRequest> get(String approvalId) {
        return Optional.ofNullable(byId.get(approvalId));
    }

    @Override
    public synchronized ApprovalRequest decide(ApprovalRequest request, ApprovalDecision decision,
                                                ApprovalStatus targetStatus) {
        ApprovalRequest stored = byId.get(request.approvalId());
        if (stored == null) {
            throw new ApprovalConflictException(
                    "Unknown approval: '" + request.approvalId() + "'", request);
        }
        if (stored.status().isTerminal()) {
            throw new ApprovalConflictException(
                    "Approval '" + stored.approvalId() + "' already " + stored.status()
                            + " - decisions are one-shot", stored);
        }
        if (decision.version() != stored.version()) {
            throw new ApprovalConflictException(
                    "Stale decision version " + decision.version() + " for approval '"
                            + stored.approvalId() + "' (current " + stored.version() + ")", stored);
        }
        // an approval only when the caller says so; this method is the raw
        // transition — the typed facade (approve/reject) lives on the
        // service layer that wraps this store.
        ApprovalRequest updated = new ApprovalRequest(
                stored.approvalId(), stored.runId(), stored.stepId(), stored.toolCallHash(),
                stored.requestedBy(), stored.riskLevel(), stored.summary(),
                stored.expiresAt(), stored.createdAt(), targetStatus, decision,
                stored.version() + 1);
        byId.put(stored.approvalId(), updated);
        replaceInRunIndex(updated);
        return updated;
    }

    @Override
    public synchronized ApprovalRequest revoke(String approvalId, ApprovalDecision revocation) {
        ApprovalRequest stored = byId.get(approvalId);
        if (stored == null) {
            throw new ApprovalConflictException("Unknown approval: '" + approvalId + "'", null);
        }
        if (stored.status() != ApprovalStatus.APPROVED) {
            throw new ApprovalConflictException(
                    "Only APPROVED requests can be revoked; '" + approvalId
                            + "' is " + stored.status(), stored);
        }
        ApprovalRequest updated = new ApprovalRequest(
                stored.approvalId(), stored.runId(), stored.stepId(), stored.toolCallHash(),
                stored.requestedBy(), stored.riskLevel(), stored.summary(),
                stored.expiresAt(), stored.createdAt(), ApprovalStatus.REVOKED, revocation,
                stored.version() + 1);
        byId.put(approvalId, updated);
        replaceInRunIndex(updated);
        return updated;
    }

    @Override
    public synchronized List<String> expireOverdue(long nowEpochMs) {
        List<String> flipped = new java.util.ArrayList<>();
        for (ApprovalRequest r : byId.values()) {
            if (r.isOverdue(nowEpochMs)) {
                ApprovalRequest expired = new ApprovalRequest(
                        r.approvalId(), r.runId(), r.stepId(), r.toolCallHash(),
                        r.requestedBy(), r.riskLevel(), r.summary(),
                        r.expiresAt(), r.createdAt(), ApprovalStatus.EXPIRED,
                        ApprovalDecision.of("system:expiry", "expired at " + nowEpochMs, r.version()),
                        r.version() + 1);
                byId.put(r.approvalId(), expired);
                replaceInRunIndex(expired);
                flipped.add(r.approvalId());
            }
        }
        return flipped;
    }

    @Override
    public List<ApprovalRequest> pendingForRun(String runId) {
        return byRun.getOrDefault(runId, List.of()).stream()
                .filter(r -> r.status() == ApprovalStatus.PENDING)
                .toList();
    }

    @Override
    public List<ApprovalRequest> allPending() {
        return byId.values().stream()
                .filter(r -> r.status() == ApprovalStatus.PENDING)
                .toList();
    }

    private void replaceInRunIndex(ApprovalRequest updated) {
        List<ApprovalRequest> list = byRun.get(updated.runId());
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).approvalId().equals(updated.approvalId())) {
                list.set(i, updated);
                return;
            }
        }
    }
}
