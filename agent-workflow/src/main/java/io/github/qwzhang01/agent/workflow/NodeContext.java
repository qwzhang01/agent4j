package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.core.run.RunContext;

/**
 * Execution context handed to a node.
 * <p>
 * Provides:
 * - {@link #state}: the shared blackboard (whole-workflow state)
 * - {@link #input}: the output of the previously executed node
 *   (or the workflow input for the first node)
 * <p>
 * additions:
 * - {@link #runId}: the Run identifier (null when called without RunManager)
 * - {@link #isResuming}: true only for the first node on resume
 *   (lets nodes like HumanApprovalNode take a different code path on resume)
 * <p>
 * (harness roadmap): {@link #runContext} exposes the unified
 * run context when the run was started with one. Nodes read tenant/user
 * identity, deadline and cancellation from it instead of free strings.
 */
public interface NodeContext {

    /**
     * The shared blackboard state of the current workflow run.
     */
    WorkflowState state();

    /**
     * Input for this node: previous node's output, or the workflow's
     * initial input if this is the first node executed.
     */
    Object input();

    /**
     * The Run identifier. Non-null when executed via RunManager;
     * null when executed via GraphRuntime.run directly.
     */
    default String runId() {
        return null;
    }

    /**
     * Whether this node is being re-executed after a pause (resume).
     * True only for the node at the checkpoint cursor; subsequent nodes
     * see false.
     */
    default boolean isResuming() {
        return false;
    }

    /**
     *  the TaskScheduler, if available. Null when not using
     * agent-scheduler module (-6 compat).
     * <p>
     * Returns Object to avoid a circular dependency (agent-scheduler
     * depends on agent-workflow, not vice versa). Nodes cast to their
     * expected scheduler type.
     */
    default Object scheduler() {
        return null;
    }

    /**
     * (harness roadmap): the unified run context, when the run
     * was started with one. Null on the legacy path (no context bound).
     * Nodes read identity/tenant/deadline/cancellation from here; the
     * free-string {@link #runId} stays for old consumers.
     */
    default RunContext runContext() {
        return null;
    }

    /**
     * Typed view of {@link #input}. Casts when possible, converts
     * via Jackson otherwise (e.g. Map -> POJO, record -> Map).
     */
    default <T> T inputAs(Class<T> type) {
        Object in = input();
        if (in == null) {
            return null;
        }
        if (type.isInstance(in)) {
            return type.cast(in);
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(in, type);
    }

    static NodeContext of(WorkflowState state, Object input) {
        return new Impl(state, input, null, false, null, null);
    }

    static NodeContext of(WorkflowState state, Object input, String runId, boolean isResuming) {
        return new Impl(state, input, runId, isResuming, null, null);
    }

    static NodeContext of(WorkflowState state, Object input, String runId, boolean isResuming, Object scheduler) {
        return new Impl(state, input, runId, isResuming, scheduler, null);
    }

    static NodeContext of(WorkflowState state, Object input, String runId, boolean isResuming,
                          Object scheduler, RunContext runContext) {
        return new Impl(state, input, runId, isResuming, scheduler, runContext);
    }

    /**
     * Default implementation.
     */
    final class Impl implements NodeContext {
        private final WorkflowState state;
        private final Object input;
        private final String runId;
        private final boolean resuming;
        private final Object scheduler;
        private final RunContext runContext;

        Impl(WorkflowState state, Object input, String runId, boolean isResuming,
             Object scheduler, RunContext runContext) {
            this.state = state;
            this.input = input;
            this.runId = runId;
            this.resuming = isResuming;
            this.scheduler = scheduler;
            this.runContext = runContext;
        }

        @Override public WorkflowState state() { return state; }
        @Override public Object input() { return input; }
        @Override public String runId() { return runId; }
        @Override public boolean isResuming() { return resuming; }
        @Override public Object scheduler() { return scheduler; }
        @Override public RunContext runContext() { return runContext; }
    }
}
