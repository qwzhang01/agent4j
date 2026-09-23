package io.github.qwzhang01.agent.core.tool.contract;

import java.util.List;

/**
 * Outcome of the argument validation.
 * <p>
 * {@code valid=false} always carries at least one human-readable error;
 * the errors are what lands in the model-visible
 * {@code [INVALID_TOOL_ARGUMENTS]} string and the audit event.
 * <p>
 * Unknown fields (present in arguments, absent from the schema's
 * {@code properties}) are policy: the harness-wide decision is
 * <b>ignore-with-record</b> — the call proceeds, but the unknown field
 * names are recorded here so audits can see what the model invented.
 * This policy is fixed at the harness level "not decided
 * per-tool".
 * <p>
 * A tool whose declared schema cannot be parsed is not a free pass: the
 * verdict flips to invalid with a {@code schema:} error, so a broken
 * contract surfaces at validation time instead of mid-execution.
 *
 * @param valid true when the arguments satisfy the contract
 * @param errors validation errors in fail order (empty when valid)
 * @param unknownFields argument fields the schema never declared
 */
public record ValidationResult(boolean valid, List<String> errors, List<String> unknownFields) {

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        unknownFields = unknownFields == null ? List.of() : List.copyOf(unknownFields);
    }

    public static ValidationResult ok(List<String> unknownFields) {
        return new ValidationResult(true, List.of(), unknownFields);
    }

    public static ValidationResult fail(List<String> errors, List<String> unknownFields) {
        return new ValidationResult(false, errors, unknownFields);
    }

    /**
     * Verdict for an unparseable / non-object schema: the contract itself is
     * broken, so validation fails closed. The schema error is prefixed to
     * keep it distinguishable from argument errors in audits.
     */
    public static ValidationResult schemaInvalid(String schemaError) {
        return new ValidationResult(false, List.of("schema: " + schemaError), List.of());
    }

    /** First error, or "invalid arguments" — for compact model-facing strings. */
    public String firstError() {
        return errors.isEmpty() ? "invalid arguments" : errors.get(0);
    }
}
