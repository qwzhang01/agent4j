package io.github.qwzhang01.agent.workflow;

/**
 * Result of one node execution.
 * <p>
 * - output: written to the blackboard under the node id, and becomes
 *   the input of the next node.
 * - next: optional explicit jump target. When non-null it takes priority
 *   over edge conditions (validated at runtime).
 *
 * @param output node output (may be null)
 * @param next   explicit next node id or END, or null to follow edges
 */
public record NodeResult(Object output, String next) {

    public static NodeResult of(Object output) {
        return new NodeResult(output, null);
    }

    public static NodeResult jump(String next, Object output) {
        return new NodeResult(output, next);
    }
}
