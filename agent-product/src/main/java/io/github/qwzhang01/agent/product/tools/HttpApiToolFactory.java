package io.github.qwzhang01.agent.product.tools;

import io.github.qwzhang01.agent.product.definition.HttpApiDecl;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds {@link HttpApiTool}s from declarations, resolving secret references
 * (Stage 13 M13.3).
 * <p>
 * Secrets never live in YAML: an {@code auth.token} of {@code ${env:NAME}} is
 * resolved against the environment at BUILD time, and a missing variable
 * refuses to load the tool (fail-fast, Stage 9 discipline) rather than shipping
 * a literal "${env:...}" string as a bearer token. A literal token passes
 * through unchanged for tests - production discipline is env references.
 */
public final class HttpApiToolFactory {

    /** Whole-value env reference: exactly ${env:NAME}, nothing else. */
    private static final Pattern ENV_REF = Pattern.compile("^\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}$");

    private final Function<String, String> envLookup;

    public HttpApiToolFactory() {
        this(System::getenv);
    }

    /**
     * @param envLookup environment accessor, injectable for tests
     */
    public HttpApiToolFactory(Function<String, String> envLookup) {
        this.envLookup = Objects.requireNonNull(envLookup, "envLookup must not be null");
    }

    /**
     * Build a tool from its declaration.
     *
     * @throws IllegalArgumentException if a ${env:...} reference cannot be resolved
     */
    public HttpApiTool create(HttpApiDecl decl) {
        Objects.requireNonNull(decl, "decl must not be null");
        String token = decl.auth() == null ? null : resolveToken(decl.auth().token());
        return new HttpApiTool(decl, token);
    }

    /**
     * Resolve an env reference or pass a literal through.
     */
    private String resolveToken(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("auth.token must not be null "
                    + "(use ${env:NAME} for secrets)");
        }
        Matcher m = ENV_REF.matcher(raw);
        if (m.matches()) {
            String value = envLookup.apply(m.group(1));
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "environment variable '" + m.group(1) + "' is not set - "
                                + "refusing to load the tool with an unresolved secret");
            }
            return value;
        }
        return raw; // literal (tests/local only - production uses ${env:...})
    }
}
