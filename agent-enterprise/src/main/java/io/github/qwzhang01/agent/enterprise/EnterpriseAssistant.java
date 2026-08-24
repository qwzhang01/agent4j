package io.github.qwzhang01.agent.enterprise;

import io.github.qwzhang01.agent.enterprise.govern.CostLedger;
import io.github.qwzhang01.agent.enterprise.govern.EnterpriseAuditTrail;
import io.github.qwzhang01.agent.enterprise.task.BusinessTask;
import io.github.qwzhang01.agent.enterprise.task.EnterpriseTaskManager;
import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.workflow.Workflow;

import java.util.Objects;
import java.util.Optional;

/**
 * The enterprise entry point (Stage 15 M15.5): one facade over the whole
 * request chain.
 * <p>
 * {@link #ask} is the synchronous path:
 * {@code requireBudget -> forRequest(ctx) -> agent.run -> record usage} -
 * identity flows explicitly through every hop (blueprint D2), governance
 * rides inside the chain (M15.3), knowledge is model-retrieved per tenant
 * (M15.2), usage is billed even when the run fails (tokens spent are tokens
 * spent - recorded in a finally block).
 * <p>
 * {@link #submitTask} is the long-running path: it delegates to the
 * {@link EnterpriseTaskManager} (approval pauses, checkpoint resume - M15.4)
 * after passing the same budget gate.
 * <p>
 * Build via {@link EnterpriseAgentFactory#builder()}.
 */
public final class EnterpriseAssistant {

    private final EnterpriseAgentFactory factory;
    private final CostLedger costLedger;                 // nullable = no budget gate
    private final EnterpriseTaskManager taskManager;     // nullable = no task path

    EnterpriseAssistant(EnterpriseAgentFactory factory,
                        CostLedger costLedger,
                        EnterpriseTaskManager taskManager) {
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
        this.costLedger = costLedger;
        this.taskManager = taskManager;
    }

    // ============ Synchronous Path ============

    /**
     * Ask a question as an authenticated user.
     *
     * @param ctx      the request context (must come from TenantRegistry.login)
     * @param question the user's question
     * @return the assistant's final answer
     * @throws io.github.qwzhang01.agent.enterprise.govern.BudgetExceededException
     *         when the tenant or user budget is exhausted (fail-closed, before
     *         any tokens are spent)
     */
    public String ask(RequestContext ctx, String question) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(question, "question must not be null");
        if (costLedger != null) {
            costLedger.requireBudget(ctx);
        }
        EnterpriseAgentFactory.EnterpriseAgent agent = factory.forRequest(ctx);
        try {
            return agent.run(question);
        } finally {
            // bill what was spent even on failure - tokens burned are tokens burned
            if (costLedger != null) {
                costLedger.record(ctx, agent.promptTokens(), agent.completionTokens());
            }
        }
    }

    // ============ Long-Running Path ============

    /**
     * Submit a long-running business task (workflow with approval nodes).
     * The same budget gate applies before anything starts.
     *
     * @throws UnsupportedOperationException when no task manager was configured
     */
    public BusinessTask submitTask(RequestContext ctx, String description, Workflow workflow) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        requireTaskManager();
        if (costLedger != null) {
            costLedger.requireBudget(ctx);
        }
        return taskManager.submit(ctx, description, workflow);
    }

    /**
     * Approve a waiting task (delegates to the task manager).
     */
    public BusinessTask approve(String taskId, String approverId, String reason) {
        requireTaskManager();
        return taskManager.approve(taskId, approverId, reason);
    }

    /**
     * Reject a waiting task (delegates to the task manager).
     */
    public BusinessTask reject(String taskId, String approverId, String reason) {
        requireTaskManager();
        return taskManager.reject(taskId, approverId, reason);
    }

    /**
     * Look up a task (delegates to the task manager).
     */
    public Optional<BusinessTask> findTask(String taskId) {
        requireTaskManager();
        return taskManager.find(taskId);
    }

    // ============ Advanced Access ============

    /**
     * Assemble the request-scoped execution chain without running it
     * (testing/inspection and non-facade entry points).
     */
    public EnterpriseAgentFactory.EnterpriseAgent forRequest(RequestContext ctx) {
        return factory.forRequest(ctx);
    }

    /**
     * The shared audit trail (byTenant/byUser/byTool cuts).
     */
    public EnterpriseAuditTrail auditTrail() {
        return factory.sharedAuditTrail();
    }

    /**
     * The cost ledger (bill queries); null when no budget was configured.
     */
    public CostLedger costLedger() {
        return costLedger;
    }

    /**
     * The task manager; null when no task path was configured.
     */
    public EnterpriseTaskManager taskManager() {
        return taskManager;
    }

    // ============ Internal ============

    private void requireTaskManager() {
        if (taskManager == null) {
            throw new UnsupportedOperationException(
                    "No task manager configured - call Builder.taskManager(...) to enable the task path");
        }
    }
}
