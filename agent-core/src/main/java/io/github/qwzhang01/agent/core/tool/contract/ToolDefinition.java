package io.github.qwzhang01.agent.core.tool.contract;

import io.github.qwzhang01.agent.core.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structured tool contract (Stage 2.1, harness roadmap).
 * <p>
 * A tool is no longer just "a JSON function the model may call" — it is a
 * runtime resource with a contract: schemas, a version, a side-effect level,
 * required capabilities and size/timeout budgets. The contract is what the
 * validation chain (Stage 2.2) enforces and what the secure assembly
 * (Stage 2.4) reads to derive default permissions.
 * <p>
 * Backward compatibility: every legacy {@link Tool} gets a definition for
 * free via {@link Tool#definition()} — the legacy adapter fills name /
 * description / inputSchema from the old accessors, marks
 * {@link SideEffectLevel#UNKNOWN} and {@code version=legacy}. Nobody is
 * forced to migrate; but secure assemblies treat UNKNOWN conservatively.
 * <p>
 * The {@code schemaFingerprint} is a SHA-256 prefix of the input schema, so
 * a trace can record "which schema version did this call actually use"
 * without storing the schema itself. Two calls with the same fingerprint
 * validated against the same contract shape.
 *
 * @param name                unique tool name (snake_case convention)
 * @param description         what the tool does, model-facing
 * @param inputSchema         JSON Schema for arguments (null = untyped legacy)
 * @param outputSchema        optional JSON Schema for results (null = unchecked)
 * @param version             contract version, free-form (default "1.0.0")
 * @param sideEffectLevel     how much external state this tool can touch
 * @param requiredCapabilities capability tokens the run context must carry
 * @param timeout             per-call execution budget (null = assembly default)
 * @param maxInputBytes       argument size cap (UNLIMITED = no cap)
 * @param maxOutputBytes      result size cap (UNLIMITED = no cap)
 * @param schemaFingerprint   SHA-256 prefix of inputSchema (auto-derived)
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchema,
        String outputSchema,
        String version,
        SideEffectLevel sideEffectLevel,
        List<String> requiredCapabilities,
        Duration timeout,
        int maxInputBytes,
        int maxOutputBytes,
        String schemaFingerprint) {

    /** Sentinel for "no size limit". */
    public static final int UNLIMITED = -1;
    /** Default input cap: 64 KiB. */
    public static final int DEFAULT_MAX_INPUT_BYTES = 64 * 1024;
    /** Default output cap: 64 KiB. */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    /** Default JSON nesting depth cap (see ToolArgumentValidator). */
    public static final int DEFAULT_MAX_DEPTH = 8;
    /** Default per-call timeout when the definition leaves it null. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /** Version tag for definitions derived from the legacy Tool API. */
    public static final String LEGACY_VERSION = "legacy";

    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(sideEffectLevel, "sideEffectLevel must not be null");
        version = version == null || version.isBlank() ? "1.0.0" : version;
        requiredCapabilities = requiredCapabilities == null
                ? List.of() : List.copyOf(requiredCapabilities);
        if (maxInputBytes == 0) {
            maxInputBytes = DEFAULT_MAX_INPUT_BYTES;
        }
        if (maxOutputBytes == 0) {
            maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        }
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
        // The fingerprint is derived, never caller-supplied: every
        // constructor path (builder, wither, copy) recomputes it, so two
        // definitions with equal schemas always carry equal fingerprints.
        schemaFingerprint = fingerprintOf(inputSchema);
    }

    /**
     * Adapt a legacy {@link Tool} into a definition. Side-effect level is
     * UNKNOWN (honest) — secure assemblies map it to the cautious branch.
     */
    public static ToolDefinition fromLegacyTool(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        return new ToolDefinition(tool.getName(), tool.getDescription(),
                tool.getParametersSchema(), null, LEGACY_VERSION,
                SideEffectLevel.UNKNOWN, List.of(), DEFAULT_TIMEOUT,
                DEFAULT_MAX_INPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES, null);
    }

    /**
     * SHA-256 prefix (16 hex chars) of the input schema. "none" when the
     * tool declares no schema — distinguishable from any real hash.
     */
    static String fingerprintOf(String schema) {
        if (schema == null || schema.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(schema.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec — unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static Builder builder(String name) {
        return new Builder(Objects.requireNonNull(name, "name must not be null"));
    }

    /** Fluent builder; unset fields fall back to the compact-constructor defaults. */
    public static final class Builder {
        private final String name;
        private String description = "";
        private String inputSchema;
        private String outputSchema;
        private String version;
        private SideEffectLevel sideEffectLevel = SideEffectLevel.UNKNOWN;
        private List<String> requiredCapabilities = List.of();
        private Duration timeout;
        private int maxInputBytes;
        private int maxOutputBytes;

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder inputSchema(String inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Builder outputSchema(String outputSchema) {
            this.outputSchema = outputSchema;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder sideEffectLevel(SideEffectLevel level) {
            this.sideEffectLevel = Objects.requireNonNull(level);
            return this;
        }

        public Builder requiredCapabilities(List<String> capabilities) {
            this.requiredCapabilities = capabilities;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxInputBytes(int maxInputBytes) {
            this.maxInputBytes = maxInputBytes;
            return this;
        }

        public Builder maxOutputBytes(int maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
            return this;
        }

        public ToolDefinition build() {
            return new ToolDefinition(name, description, inputSchema, outputSchema,
                    version, sideEffectLevel, requiredCapabilities, timeout,
                    maxInputBytes, maxOutputBytes, null);
        }
    }
}
