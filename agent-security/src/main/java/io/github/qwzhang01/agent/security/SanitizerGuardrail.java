package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.agent.Guardrail;
import io.github.qwzhang01.agent.core.agent.GuardrailContext;
import io.github.qwzhang01.agent.core.agent.GuardrailFailMode;
import io.github.qwzhang01.agent.core.agent.GuardrailPhase;
import io.github.qwzhang01.agent.core.agent.GuardrailVerdict;

import java.util.Objects;

/**
 * KP5 adapter: reuse Stage 9 {@link ResultSanitizer} as an input or output door.
 * <p>
 * {@link DefaultResultSanitizer.Strategy#BLOCK} becomes {@link GuardrailVerdict.Block}.
 * SANITIZE / TRUNCATE become {@link GuardrailVerdict.Rewrite}. Clean text is Allow.
 */
public final class SanitizerGuardrail implements Guardrail {

    private final String name;
    private final GuardrailPhase phase;
    private final GuardrailFailMode failMode;
    private final ResultSanitizer sanitizer;
    private final boolean blockOnHit;

    public SanitizerGuardrail(String name, GuardrailPhase phase, GuardrailFailMode failMode,
                              ResultSanitizer sanitizer, boolean blockOnHit) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.phase = Objects.requireNonNull(phase, "phase");
        this.failMode = Objects.requireNonNull(failMode, "failMode");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.blockOnHit = blockOnHit;
    }

    public static SanitizerGuardrail input(ResultSanitizer sanitizer, GuardrailFailMode failMode) {
        boolean block = sanitizer instanceof DefaultResultSanitizer def
                && def.strategy() == DefaultResultSanitizer.Strategy.BLOCK;
        return new SanitizerGuardrail("input-sanitizer", GuardrailPhase.INPUT, failMode, sanitizer, block);
    }

    public static SanitizerGuardrail output(ResultSanitizer sanitizer, GuardrailFailMode failMode) {
        boolean block = sanitizer instanceof DefaultResultSanitizer def
                && def.strategy() == DefaultResultSanitizer.Strategy.BLOCK;
        return new SanitizerGuardrail("output-sanitizer", GuardrailPhase.OUTPUT, failMode, sanitizer, block);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public GuardrailPhase phase() {
        return phase;
    }

    @Override
    public GuardrailFailMode failMode() {
        return failMode;
    }

    @Override
    public GuardrailVerdict evaluate(GuardrailContext context) {
        SanitizeResult result = sanitizer.sanitize(context.text());
        if (!result.modified()) {
            return new GuardrailVerdict.Allow();
        }
        if (blockOnHit) {
            return new GuardrailVerdict.Block(result.reason() == null ? name : result.reason());
        }
        return new GuardrailVerdict.Rewrite(result.sanitized(), result.reason());
    }
}
