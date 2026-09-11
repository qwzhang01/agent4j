package io.github.qwzhang01.agent.core.agent;

/**
 * Outcome of one {@link Guardrail} evaluation (KP5).
 */
public sealed interface GuardrailVerdict {

    record Allow() implements GuardrailVerdict {
    }

    /**
     * Replace the evaluated text. For INPUT, only the model request changes.
     * For OUTPUT, the rewrite is what gets persisted and shown.
     */
    record Rewrite(String replacement, String reason) implements GuardrailVerdict {
        public Rewrite {
            if (replacement == null) {
                throw new IllegalArgumentException("replacement must not be null");
            }
        }
    }

    /** Refuse. INPUT stops the loop; OUTPUT discards the model answer. */
    record Block(String reason) implements GuardrailVerdict {
        public Block {
            if (reason == null || reason.isBlank()) {
                reason = "blocked by guardrail";
            }
        }
    }
}
