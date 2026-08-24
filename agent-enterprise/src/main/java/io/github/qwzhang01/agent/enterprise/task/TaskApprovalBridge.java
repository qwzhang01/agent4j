package io.github.qwzhang01.agent.enterprise.task;

import io.github.qwzhang01.agent.workflow.ApprovalService;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The assembly-level approval channel between workflows and task managers
 * (Stage 15 M15.4).
 * <p>
 * Two sides meet here: the workflow side ({@link HumanApprovalNode} wired
 * with this service) asks for decisions as runs pause; the manager side
 * ({@link EnterpriseTaskManager#approve}/{@code reject}) writes the answers.
 * <p>
 * Why a separate, assembly-scoped class (and not a private manager inner
 * class): <b>the bridge must outlive any single manager instance</b>.
 * Workflows are immutable definitions that capture the service instance at
 * node construction; after a crash, the recovering manager is a NEW object -
 * if the node's service were bound to the dead manager's private state, the
 * recovered run could never see the new manager's decisions. The bridge is
 * created once per assembly (or per recovery) and shared by the workflow
 * nodes and every manager that handles the task.
 * <p>
 * The decision table is deliberately in-memory only: after a restart it is
 * empty, which is exactly the correct semantics - a decision that was never
 * consumed must be given again, not replayed from stale state
 * ({@link EnterpriseTaskManager#recover} relies on this).
 * <p>
 * v1 assumption: one pending approval node per run (decisions keyed by
 * runId). Multi-gate workflows approve gates one at a time - each gate's
 * pause overwrites the previous consumed decision.
 */
public final class TaskApprovalBridge implements ApprovalService {

    private final Map<String, Boolean> decisions = new ConcurrentHashMap<>();
    private final Map<String, String> pending = new ConcurrentHashMap<>();

    // ============ Workflow Side (called by HumanApprovalNode) ============

    @Override
    public boolean approve(Request request) {
        // sync mode is not used by this profile (tasks always run via RunManager)
        throw new UnsupportedOperationException(
                "Sync approval is not supported by TaskApprovalBridge; "
                        + "use EnterpriseTaskManager.approve/reject");
    }

    @Override
    public void requestApproval(String runId, String nodeId, String summary, Object payload) {
        Objects.requireNonNull(runId, "runId must not be null");
        pending.put(runId, nodeId + ": " + summary);
    }

    @Override
    public Boolean checkDecision(String runId, String nodeId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return decisions.get(runId);
    }

    // ============ Manager Side (called by EnterpriseTaskManager) ============

    /**
     * Record the decision a manager settled for the run's pending approval
     * node. The next resume of that run reads it via {@link #checkDecision}.
     */
    public void decide(String runId, boolean approved) {
        Objects.requireNonNull(runId, "runId must not be null");
        decisions.put(runId, approved);
        pending.remove(runId);
    }

    /**
     * Forget every pending request and decision (test/reset semantics; also
     * models "fresh table after restart").
     */
    public void reset() {
        decisions.clear();
        pending.clear();
    }
}
