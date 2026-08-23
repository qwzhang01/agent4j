package io.github.qwzhang01.agent.product.prompt;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Prompt-as-asset management (Stage 13 M13.4, D4): versioning, two-channel
 * release, tenant routing, rollback - all as APPEND-ONLY history.
 * <p>
 * The mental model is a package registry, not a config file:
 * <ul>
 *   <li>{@link #publish} appends an immutable version and points a channel at it</li>
 *   <li>{@link #resolve} is the routing point: tenant override &gt; declared
 *       channel (default stable) - it answers "which version does THIS caller
 *       see right now"</li>
 *   <li>{@link #rollback} moves the stable pointer one step back; stored
 *       content is never rewritten (history stays honest)</li>
 * </ul>
 * <p>
 * PIN semantics (the D4 acceptance): resolve is called at AGENT-BIND time by
 * {@code AgentDefinitionBinder} and the content is snapshotted into the agent
 * instance. A running conversation therefore keeps its prompt even while a
 * new version ships; the next bind picks the new one. In the product shape
 * where one agent instance serves one conversation (the channel-layer
 * mapping), instance-level pin IS conversation-level pin.
 */
public final class PromptManager {

    private final Clock clock;
    private final Map<String, PromptRecord> prompts = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> tenantOverrides = new HashMap<>();

    public PromptManager() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock injectable for deterministic timestamps in tests
     */
    public PromptManager(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ============ Publish ============

    /**
     * Publish a new version to the stable channel.
     */
    public PromptVersion publish(String name, String content) {
        return publish(name, content, PromptChannel.STABLE);
    }

    /**
     * Publish a new immutable version to the given channel and point that
     * channel at it.
     *
     * @throws IllegalArgumentException on blank name/content or invalid channel
     */
    public PromptVersion publish(String name, String content, String channel) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("prompt name must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("prompt content must not be null");
        }
        PromptChannel.requireValid(channel);

        PromptRecord record = prompts.computeIfAbsent(name, k -> new PromptRecord());
        PromptVersion version = new PromptVersion(
                name, record.versions.size() + 1, content, channel, clock.instant());
        record.versions.add(version);
        record.channelIndex.put(channel, record.versions.size() - 1);
        return version;
    }

    // ============ Resolve (routing point) ============

    /**
     * Which version does this caller see right now?
     * <p>
     * Channel priority: tenant override &gt; declared channel &gt; stable.
     *
     * @param name            prompt name
     * @param tenantId        optional tenant id (may unlock a canary override)
     * @param declaredChannel the channel the DEFINITION asked for (null = stable)
     * @return the routed version, empty if the prompt/channel has no version
     */
    public Optional<PromptVersion> resolve(String name, String tenantId, String declaredChannel) {
        PromptRecord record = prompts.get(name);
        if (record == null) {
            return Optional.empty();
        }
        String channel = routeChannel(name, tenantId, declaredChannel);
        Integer index = record.channelIndex.get(channel);
        return index == null ? Optional.empty() : Optional.of(record.versions.get(index));
    }

    private String routeChannel(String name, String tenantId, String declaredChannel) {
        if (tenantId != null && !tenantId.isBlank()) {
            Map<String, String> overrides = tenantOverrides.get(tenantId);
            if (overrides != null) {
                String override = overrides.get(name);
                if (override != null) {
                    return override;
                }
            }
        }
        return declaredChannel == null ? PromptChannel.STABLE : declaredChannel;
    }

    // ============ Tenant routing (canary) ============

    /**
     * Route ONE tenant's prompt to a specific channel (the canary knob).
     */
    public PromptManager setTenantChannel(String tenantId, String promptName, String channel) {
        PromptChannel.requireValid(channel);
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        tenantOverrides.computeIfAbsent(tenantId, k -> new HashMap<>()).put(promptName, channel);
        return this;
    }

    /**
     * Remove a tenant override - the tenant falls back to declared/stable.
     */
    public PromptManager clearTenantChannel(String tenantId, String promptName) {
        Map<String, String> overrides = tenantOverrides.get(tenantId);
        if (overrides != null) {
            overrides.remove(promptName);
        }
        return this;
    }

    // ============ Rollback ============

    /**
     * Move the stable pointer one step back (to the previous version that was
     * published to stable). Content is never rewritten - the history stays
     * exactly as published.
     *
     * @throws IllegalArgumentException if stable has no earlier version to go back to
     */
    public PromptVersion rollback(String name) {
        PromptRecord record = prompts.get(name);
        if (record == null) {
            throw new IllegalArgumentException("prompt '" + name + "' is not published");
        }
        Integer current = record.channelIndex.get(PromptChannel.STABLE);
        if (current == null) {
            throw new IllegalArgumentException("prompt '" + name + "' has no stable version");
        }
        for (int i = current - 1; i >= 0; i--) {
            if (PromptChannel.STABLE.equals(record.versions.get(i).channel())) {
                record.channelIndex.put(PromptChannel.STABLE, i);
                return record.versions.get(i);
            }
        }
        throw new IllegalArgumentException(
                "prompt '" + name + "' stable pointer is already at the earliest version");
    }

    // ============ Inspection ============

    /**
     * Immutable view of the version history (append-only audit trail).
     */
    public List<PromptVersion> history(String name) {
        PromptRecord record = prompts.get(name);
        return record == null ? List.of() : List.copyOf(record.versions);
    }

    public Set<String> promptNames() {
        return new HashSet<>(prompts.keySet());
    }

    // --------------------------------------------
    // Internals
    // --------------------------------------------

    private static final class PromptRecord {
        final List<PromptVersion> versions = new ArrayList<>();
        /** channel -> index into versions (the channel pointer). */
        final Map<String, Integer> channelIndex = new HashMap<>();
    }
}
