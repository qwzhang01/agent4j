package io.github.qwzhang01.agent.trace.trajectory;

/**
 * The Observation half of one trajectory step: the result of one tool call
 * (Stage 14 D3).
 * <p>
 * {@code content} is recorded VERBATIM - whatever the executor returned is
 * what the model saw on the next call, including Stage 2 "[ERROR] ..." texts
 * and Stage 9 "[DENIED]" governance texts. The trajectory never rewrites or
 * interprets observations (D1: record what the policy actually saw).
 * <p>
 * {@code success} has a narrow honest meaning: the executor RETURNED instead
 * of throwing. "[ERROR]" results from DefaultToolExecutor's error wrapping
 * count as success=true - that text is a normal observation from the loop's
 * point of view. Only an executor-level exception (rare, a framework bug)
 * yields success=false.
 *
 * @param toolCallId tool call id the model assigned
 * @param name       tool name
 * @param content    result text exactly as returned to the loop
 * @param success    false only if the executor threw
 * @param durationMs wall time of the tool execution
 */
public record ToolObservation(
        String toolCallId,
        String name,
        String content,
        boolean success,
        long durationMs
) {
}
