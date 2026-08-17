package io.github.qwzhang01.agent.workflow;

/**
 * Execution context handed to a node.
 * <p>
 * Provides:
 * - {@link #state()}: the shared blackboard (whole-workflow state)
 * - {@link #input()}: the output of the previously executed node
 * (or the workflow input for the first node)
 */
public interface NodeContext {

    /**
     * Internal factory.
     */
    static NodeContext of(WorkflowState state, Object input) {
        return new Impl(state, input);
    }

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

    /**
     * Default implementation.
     */
    final class Impl implements NodeContext {
        private final WorkflowState state;
        private final Object input;

        Impl(WorkflowState state, Object input) {
            this.state = state;
            this.input = input;
        }

        @Override
        public WorkflowState state() {
            return state;
        }

        @Override
        public Object input() {
            return input;
        }
    }
}
