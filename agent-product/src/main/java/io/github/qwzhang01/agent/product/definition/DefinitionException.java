package io.github.qwzhang01.agent.product.definition;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Fail-fast rejection of a definition: either it could not be parsed (syntax/unknown
 * field, with cause) or it failed semantic validation (carries all errors).
 * <p>
 * Aligned with the framework's fail-closed philosophy (Stage 9/12): a broken
 * definition refuses to start the platform rather than degrading silently.
 * Tests assert on {@link #getErrors()} / cause, not on message strings.
 */
public final class DefinitionException extends RuntimeException {

    private final List<ValidationError> errors;

    /**
     * Parse failure (syntax error, unknown field, IO problem).
     */
    public DefinitionException(String message, Throwable cause) {
        super(message, cause);
        this.errors = List.of();
    }

    /**
     * Validation failure - carries ALL errors so the author fixes everything in one pass.
     */
    public DefinitionException(List<ValidationError> errors) {
        super("Definition rejected with " + errors.size() + " error(s):\n  "
                + errors.stream().map(ValidationError::toString).collect(Collectors.joining("\n  ")));
        this.errors = List.copyOf(errors);
    }

    /**
     * Semantic errors; empty when this exception wraps a parse failure.
     */
    public List<ValidationError> getErrors() {
        return errors;
    }
}
