package io.github.qwzhang01.agent.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-friendly ApprovalService supporting both sync (Stage 5) and
 * async (Stage 6) modes.
 * <p>
 * Sync mode: returns a fixed decision immediately.
 * Async mode: records requests, returns decisions set via {@link #setDecision}.
 */
public final class MockApprovalService implements ApprovalService {

    private final boolean syncDecision;
    private final AtomicInteger syncCalls = new AtomicInteger();

    // Async state
    private final List<Request> asyncRequests = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> asyncDecisions = new ConcurrentHashMap<>();

    public MockApprovalService(boolean syncDecision) {
        this.syncDecision = syncDecision;
    }

    public static MockApprovalService autoApprove() {
        return new MockApprovalService(true);
    }

    public static MockApprovalService autoReject() {
        return new MockApprovalService(false);
    }

    // ============ Sync (Stage 5) ============

    @Override
    public boolean approve(Request request) {
        syncCalls.incrementAndGet();
        return syncDecision;
    }

    public int callCount() {
        return syncCalls.get();
    }

    // ============ Async (Stage 6) ============

    @Override
    public void requestApproval(String runId, String nodeId, String summary, Object payload) {
        asyncRequests.add(new Request(nodeId, summary, payload));
    }

    @Override
    public Boolean checkDecision(String runId, String nodeId) {
        return asyncDecisions.get(runId + ":" + nodeId);
    }

    /**
     * Test helper: set the approval decision for a run+node.
     * Call this between pause and resume.
     */
    public void setDecision(String runId, String nodeId, boolean approved) {
        asyncDecisions.put(runId + ":" + nodeId, approved);
    }

    /** Async requests received so far (for test assertions). */
    public List<Request> asyncRequests() {
        return List.copyOf(asyncRequests);
    }
}
