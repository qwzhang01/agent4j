package io.github.qwzhang01.agent.workflow;

/**
 * Execution context handed to a node.
 * <p>
 * Provides:
 * - {@link #state()}: the shared blackboard (whole-workflow state)
 * - {@link #input()}: the output of the previously executed node
 *   (or the workflow input for the first node)
 * <p>
 * Stage 6 additions:
 * - {@link #runId()}: the Run identifier (null when called without RunManager)
 * - {@link #isResuming()}: true only for the first node on resume
 *   (lets nodes like HumanApprovalNode take a different code path on resume)
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
     * null when executed via GraphRuntime.run() directly.
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
     * Typed view of {@link #input()}. Casts when possible, converts
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

    // ============ Factories ============

    /** Stage 5 compat: no runId, not resuming. */
    static NodeContext of(WorkflowState state, Object input) {
        return new Impl(state, input, null, false);
    }

    /** Stage 6: with runId and resume flag. */
    static NodeContext of(WorkflowState state, Object input, String runId, boolean isResuming) {
        return new Impl(state, input, runId, isResuming);
    }

    /**
     * Default implementation.
     */
    final class Impl implements NodeContext {
        private final WorkflowState state;
        private final Object input;
        private final String runId;
        private final boolean resuming;

        Impl(WorkflowState state, Object input, String runId, boolean isResuming) {
            this.state = state;
            this.input = input;
            this.runId = runId;
            this.resuming = isResuming;
        }

        @Override public WorkflowState state() { return state; }
        @Override public Object input() { return input; }
        @Override public String runId() { return runId; }
        @Override public boolean isResuming() { return resuming; }
    }
}
