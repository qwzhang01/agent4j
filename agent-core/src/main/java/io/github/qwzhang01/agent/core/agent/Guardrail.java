package io.github.qwzhang01.agent.core.agent;

/**
 * One input or output rule (KP5). Tool-time governance stays on
 * {@code GovernedToolExecutor}; this is the door before the model and the
 * door before the user.
 */
public interface Guardrail {

    String name();

    GuardrailPhase phase();

    /** Required. No implicit default — see {@link GuardrailFailMode}. */
    GuardrailFailMode failMode();

    GuardrailVerdict evaluate(GuardrailContext context);
}
