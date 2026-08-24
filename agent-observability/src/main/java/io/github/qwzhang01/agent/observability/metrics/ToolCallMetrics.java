package io.github.qwzhang01.agent.observability.metrics;

/**
 * Operational facts of one tool call, captured at the ToolExecutor boundary.
 *
 * @param toolName  tool being invoked
 * @param latencyMs wall-clock latency around the delegate executor
 * @param success   whether the tool actually executed and produced a result;
 *                  governance denials report {@code success=false} because the
 *                  tool never ran; error-wrapped texts ("[ERROR] ...", Stage 2)
 *                  report {@code success=true} because the tool DID run and the
 *                  wrapped text is a normal observation the model saw
 * @param denied    whether the governance chain blocked this call (Stage 9
 *                  {@code GovernedToolExecutor} returns results prefixed with
 *                  "[DENIED] " / "[RATE_LIMITED] " - see
 *                  {@link ObservingToolExecutor} for the wiring-order contract)
 * @param error     failure description when the executor itself threw, null otherwise
 */
public record ToolCallMetrics(
        String toolName,
        long latencyMs,
        boolean success,
        boolean denied,
        String error) {
}
