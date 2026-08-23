package io.github.qwzhang01.agent.product.template;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * A declared template variable (Stage 13 M13.2).
 * <p>
 * A variable is a named hole in the template's spec tree, written as
 * {@code ${name}}. At instantiate time every placeholder is replaced with the
 * parameter value (or the declared default). Declaring variables up front is
 * what makes typos fail fast: a placeholder for an undeclared variable rejects
 * the template at load time, and an undeclared parameter rejects the call.
 *
 * @param name         variable name referenced as {@code ${name}}
 * @param required     whether instantiate must receive a value (or a default)
 * @param defaultValue value used when the parameter is absent (implies optional)
 * @param description  human-readable hint for template authors
 * @param type         documentation-only in v1 (no type system yet)
 */
public record VariableDecl(
        String name,
        boolean required,
        @JsonProperty("default") String defaultValue,
        String description,
        String type) {

    public VariableDecl {
        Objects.requireNonNull(name, "variable name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
    }

    /**
     * A required variable without a default.
     */
    public static VariableDecl required(String name) {
        return new VariableDecl(name, true, null, null, null);
    }

    /**
     * An optional variable with a default.
     */
    public static VariableDecl optional(String name, String defaultValue) {
        return new VariableDecl(name, false, defaultValue, null, null);
    }
}
