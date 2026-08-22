package io.github.qwzhang01.agent.channel.identity;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The resource scope granted to a service identity (Stage 12 D4).
 * <p>
 * An agent never inherits the invoking user's permissions wholesale. Its
 * reachable resources are an explicitly granted minimal set, declared in
 * three dimensions:
 * <ul>
 *   <li>{@code capabilities} - coarse-grained capability strings, e.g.
 *       "git.read", "crm.read", "ci.trigger"</li>
 *   <li>{@code memoryScopes} - memory namespaces this identity may read,
 *       in MemoryScope string form, e.g. "channel:team-eng", "agent:eng-bot"
 *       (format-compatible with agent-memory's MemoryScope, kept as strings
 *       so the identity layer stays dependency-free)</li>
 *   <li>{@code dataClassifications} - data classification levels this
 *       identity may touch, e.g. "public", "internal"</li>
 * </ul>
 * <p>
 * Capabilities combine with the invoking user's channel role permissions
 * via intersect semantics (see {@link IdentityResolver}); memory scopes and
 * data classifications are granted-only in v1 (the user side has no
 * corresponding input yet - documented honestly, not hidden).
 *
 * @param capabilities        granted capability strings (never null)
 * @param memoryScopes        granted memory namespaces (never null)
 * @param dataClassifications granted data classifications (never null)
 */
public record IdentityScope(
        Set<String> capabilities,
        Set<String> memoryScopes,
        Set<String> dataClassifications
) {

    public IdentityScope {
        capabilities = immutableCopy(capabilities, "capabilities");
        memoryScopes = immutableCopy(memoryScopes, "memoryScopes");
        dataClassifications = immutableCopy(dataClassifications, "dataClassifications");
    }

    // ============ Factory Methods ============

    /**
     * A scope granting only capabilities (no memory / classification access).
     */
    public static IdentityScope capabilities(String... caps) {
        return new IdentityScope(Set.of(caps), Set.of(), Set.of());
    }

    /**
     * The empty scope: grants nothing. Useful as a safe default.
     */
    public static IdentityScope empty() {
        return new IdentityScope(Set.of(), Set.of(), Set.of());
    }

    // ============ Predicates ============

    /**
     * Whether this scope grants the given capability.
     */
    public boolean allows(String capability) {
        return capability != null && capabilities.contains(capability);
    }

    /**
     * Whether this scope may read the given memory namespace string
     * (e.g. "channel:team-eng").
     */
    public boolean canReadMemoryScope(String scope) {
        return scope != null && memoryScopes.contains(scope);
    }

    // ============ Combination ============

    /**
     * Element-wise intersection of all three sets.
     * <p>
     * This is the core of Stage 12 D4: effective permissions =
     * granted scope INTERSECT user role permissions. A capability present
     * on only one side does not survive.
     */
    public IdentityScope intersect(IdentityScope other) {
        Objects.requireNonNull(other, "other scope must not be null");
        return new IdentityScope(
                intersect(capabilities, other.capabilities),
                intersect(memoryScopes, other.memoryScopes),
                intersect(dataClassifications, other.dataClassifications));
    }

    /**
     * Whether this scope grants anything at all.
     */
    public boolean isEmpty() {
        return capabilities.isEmpty() && memoryScopes.isEmpty() && dataClassifications.isEmpty();
    }

    // ============ Helpers ============

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> immutableCopy(Set<String> set, String name) {
        Objects.requireNonNull(set, name + " must not be null");
        return Set.copyOf(set);
    }
}
