package io.github.qwzhang01.agent.trace.trajectory;

import io.github.qwzhang01.agent.core.model.ModelResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Metadata of one trajectory (Stage 14): who ran, with what config,
 * for how long, at what token cost.
 * <p>
 * Configuration identity is intentionally fingerprint-based (sha256 of the
 * system prompt) rather than a dependency on Stage 13 PromptManager - the
 * same scheme treats code-built agents and YAML-built agents equally.
 * PromptManager version names can be attached via {@code custom} by the
 * assembling layer.
 * <p>
 * Fields populated from an attached {@code AgentConfig} (agentName /
 * promptSha256 / tools / maxSteps) are null/empty when the recorder was wired
 * manually without {@link io.github.qwzhang01.agent.trace.record.RunSession#attach}
 * - absence of metadata is honest, fabricated metadata is not.
 *
 * @param agentName      agent name from config (null if not attached)
 * @param promptSha256   sha256 hex of the system prompt (null if none/unknown)
 * @param tools          registered tool names (empty if not attached)
 * @param maxSteps       configured max steps (null if not attached)
 * @param startedAt      session open time
 * @param finishedAt     session finish time
 * @param durationMs     wall time of the whole run
 * @param tokenUsage     aggregated usage over all model calls (never null; zeros when providers report nothing)
 * @param lastError      terminal error text (null on clean runs)
 * @param custom         free-form key-values for the assembling layer (never null)
 */
public record TrajectoryMetadata(
        String agentName,
        String promptSha256,
        List<String> tools,
        Integer maxSteps,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        ModelResponse.TokenUsage tokenUsage,
        String lastError,
        Map<String, String> custom
) {
    public TrajectoryMetadata {
        tools = tools == null ? List.of() : List.copyOf(tools);
        custom = custom == null ? Map.of() : Map.copyOf(custom);
        tokenUsage = tokenUsage == null
                ? new ModelResponse.TokenUsage(0, 0, 0)
                : tokenUsage;
    }

    /**
     * sha256 of a text as lowercase hex; null input yields null.
     * Shared helper so tests and the session compute fingerprints identically.
     */
    public static String sha256Hex(String text) {
        if (text == null) {
            return null;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS spec; unreachable in practice
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
