package io.github.qwzhang01.agent.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Ordered {@link Guardrail} list. First {@link GuardrailVerdict.Block} wins.
 * Rewrites compose: each later rule sees the previous replacement.
 */
public final class GuardrailChain {

    private static final Logger log = LoggerFactory.getLogger(GuardrailChain.class);
    private static final GuardrailChain NONE = new GuardrailChain(List.of());

    private final List<Guardrail> rules;

    private GuardrailChain(List<Guardrail> rules) {
        this.rules = List.copyOf(rules);
    }

    public static GuardrailChain none() {
        return NONE;
    }

    public static GuardrailChain of(Guardrail... rules) {
        return new GuardrailChain(List.of(rules));
    }

    public static GuardrailChain of(List<Guardrail> rules) {
        return new GuardrailChain(Objects.requireNonNull(rules, "rules"));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public List<Guardrail> rules() {
        return rules;
    }

    public GuardrailVerdict evaluate(GuardrailContext context) {
        Objects.requireNonNull(context, "context");
        String text = context.text();
        String rewritten = null;
        String rewriteReason = null;
        for (Guardrail rule : rules) {
            if (rule.phase() != context.phase()) {
                continue;
            }
            GuardrailVerdict verdict;
            try {
                verdict = rule.evaluate(new GuardrailContext(
                        context.phase(), text, context.config(), context.state()));
            } catch (RuntimeException e) {
                if (rule.failMode() == GuardrailFailMode.FAIL_CLOSED) {
                    log.warn("[Guardrail] '{}' failed closed: {}", rule.name(), e.toString());
                    return new GuardrailVerdict.Block(rule.name() + " failed closed: " + e.getMessage());
                }
                log.warn("[Guardrail] '{}' failed open, skipping: {}", rule.name(), e.toString());
                continue;
            }
            if (verdict instanceof GuardrailVerdict.Block block) {
                return block;
            }
            if (verdict instanceof GuardrailVerdict.Rewrite rewrite) {
                text = rewrite.replacement();
                rewritten = text;
                rewriteReason = rewrite.reason();
            }
        }
        return rewritten != null
                ? new GuardrailVerdict.Rewrite(rewritten, rewriteReason)
                : new GuardrailVerdict.Allow();
    }
}
