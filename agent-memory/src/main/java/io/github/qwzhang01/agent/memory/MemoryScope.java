package io.github.qwzhang01.agent.memory;

import java.util.Objects;

/**
 * A memory namespace - the single mechanism for both isolation and sharing (Stage 8 D3).
 * <p>
 * Scope string format: {@code <kind>:<id>}, e.g.
 * <ul>
 *   <li>{@code agent:weather-bot} - this agent's own knowledge</li>
 *   <li>{@code user:u1} - a user's personal memories (private)</li>
 *   <li>{@code session:s7} - session-level facts</li>
 *   <li>{@code task:r42} - task working memory (maps to a Stage 7 AsyncTask)</li>
 *   <li>{@code channel:c1} - channel-shared memory (multi-user, Claude Tag style)</li>
 * </ul>
 * <p>
 * Sharing is just a scope value, not a separate system. Multi-tenant isolation is
 * enforced by the store: queries only ever return entries whose scope is in the
 * explicitly provided scope list.
 *
 * @param value the scope string, e.g. "user:u1"
 */
public record MemoryScope(String value) {

    /**
     * The kind of namespace a scope represents.
     */
    public enum Kind {
        AGENT,
        USER,
        SESSION,
        TASK,
        CHANNEL
    }

    // ============ Factory Methods ============

    public static MemoryScope agent(String name) {
        return new MemoryScope("agent:" + name);
    }

    public static MemoryScope user(String userId) {
        return new MemoryScope("user:" + userId);
    }

    public static MemoryScope session(String sessionId) {
        return new MemoryScope("session:" + sessionId);
    }

    public static MemoryScope task(String runId) {
        return new MemoryScope("task:" + runId);
    }

    public static MemoryScope channel(String channelId) {
        return new MemoryScope("channel:" + channelId);
    }

    /**
     * Parse a raw scope string. Validates the format.
     */
    public static MemoryScope of(String value) {
        Objects.requireNonNull(value, "scope value must not be null");
        if (value.isBlank() || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid scope format, expected '<kind>:<id>', got: " + value);
        }
        return new MemoryScope(value.trim());
    }

    // ============ Accessors ============

    /**
     * The namespace kind (the part before the colon).
     */
    public Kind kind() {
        String prefix = value.substring(0, value.indexOf(':'));
        return Kind.valueOf(prefix.toUpperCase());
    }

    /**
     * The identifier within the namespace (the part after the colon).
     */
    public String id() {
        return value.substring(value.indexOf(':') + 1);
    }
}
