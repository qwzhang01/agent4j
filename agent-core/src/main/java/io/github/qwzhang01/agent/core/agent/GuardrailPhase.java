package io.github.qwzhang01.agent.core.agent;

/**
 * When a {@link Guardrail} runs (KP5).
 * <p>
 * Three timings, three failure semantics. The middle layer (tool) is already
 * {@code GovernedToolExecutor}; this enum covers the two missing doors:
 * <ul>
 *   <li>{@link #INPUT} — after {@link ContextBuilder}, before the model call.
 *       Block = refuse the turn; Rewrite = model sees the rewrite, state keeps the original.</li>
 *   <li>{@link #OUTPUT} — after the model’s final answer, before it is written
 *       to {@link AgentState} or emitted as {@link AgentEvent.Done}.
 *       Block = do not show the answer; Rewrite = persist and emit the rewrite.</li>
 * </ul>
 */
public enum GuardrailPhase {
    INPUT,
    OUTPUT
}
