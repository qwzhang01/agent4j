package io.github.qwzhang01.agent.product.definition;

/**
 * A single validation failure with its location in the definition.
 * <p>
 * Errors are addressed to the business author, not to a Java stack trace:
 * the path points into the YAML structure (e.g. {@code spec.tools[0].ref}) and the
 * message lists what went wrong plus what is available (D8: validation is the
 * product's first impression; fail fast, never degrade silently).
 *
 * @param path    dotted path into the definition (e.g. "spec.model.provider")
 * @param message human-readable explanation, includes available options where useful
 */
public record ValidationError(String path, String message) {

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
