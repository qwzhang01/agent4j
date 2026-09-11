package io.github.qwzhang01.agent.core.agent;

/**
 * What happens when a {@link Guardrail} itself throws (KP5).
 * <p>
 * There is no implicit default: every rule declares one. A throwing
 * fail-closed rule becomes a {@link GuardrailVerdict.Block}; a throwing
 * fail-open rule is skipped and the next rule runs.
 */
public enum GuardrailFailMode {
    FAIL_CLOSED,
    FAIL_OPEN
}
