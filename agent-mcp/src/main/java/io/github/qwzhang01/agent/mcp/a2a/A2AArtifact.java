package io.github.qwzhang01.agent.mcp.a2a;

import java.util.Objects;

/**
 * One output artifact of an A2A task (spec dialect).
 * <p>
 * The spec's Artifact carries {@code parts} (text / file / data kinds). v1
 * models exactly the text kind: one artifact, one text -- which is what an
 * {@link io.github.qwzhang01.agent.core.agent.Agent} produces anyway (its
 * {@code run} returns a String). File and data parts are a v2 concern;
 * {@link A2AJson} skips unknown part kinds on parse instead of rejecting,
 * so a richer spec peer still round-trips the text we understand.
 *
 * @param artifactId spec artifactId (stable within the task)
 * @param name       human-readable name (nullable)
 * @param text       the single TextPart's content
 */
public record A2AArtifact(String artifactId, String name, String text) {

    public A2AArtifact {
        Objects.requireNonNull(artifactId, "artifactId must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }

    /** Convenience factory for the common "one text answer" artifact. */
    public static A2AArtifact text(String artifactId, String text) {
        return new A2AArtifact(artifactId, "response", text);
    }
}
