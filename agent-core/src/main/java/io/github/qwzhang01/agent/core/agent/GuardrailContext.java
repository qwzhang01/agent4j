package io.github.qwzhang01.agent.core.agent;

/**
 * Snapshot a {@link Guardrail} evaluates. {@code text} is the last USER turn
 * for {@link GuardrailPhase#INPUT}, or the model’s final answer for
 * {@link GuardrailPhase#OUTPUT}.
 */
public record GuardrailContext(
        GuardrailPhase phase,
        String text,
        AgentConfig config,
        AgentState state
) {
    public GuardrailContext {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        if (text == null) {
            text = "";
        }
    }
}
