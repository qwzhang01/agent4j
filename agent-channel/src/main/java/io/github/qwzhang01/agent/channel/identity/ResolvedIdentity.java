package io.github.qwzhang01.agent.channel.identity;

import java.time.Instant;
import java.util.Set;

/**
 * The result of a successful identity resolution (Stage 12 D4).
 * <p>
 * A resolved identity is what a channel run executes under. Its
 * {@link #effectiveCapabilities()} is the intersection of the agent's
 * granted capabilities and the invoking user's channel role capabilities -
 * never the union, never the user's full permission set.
 * <p>
 * Audit attribution: the actor of any action taken under this identity is
 * the service account ({@link #actor()}), with the invoking user recorded
 * as context - "the agent did it, on behalf of user X, with capability Y".
 *
 * @param channelId             where the run happens
 * @param userId                who invoked the agent (context, NOT the actor)
 * @param identity              the agent identity (the actor)
 * @param serviceAccountId      the service account id
 * @param effectiveCapabilities granted INTERSECT role capabilities
 * @param grantedScope          the original granted scope (memory scopes and
 *                              data classifications are granted-only in v1)
 * @param resolvedAt            resolution timestamp
 */
public record ResolvedIdentity(
        String channelId,
        String userId,
        AgentIdentity identity,
        String serviceAccountId,
        Set<String> effectiveCapabilities,
        IdentityScope grantedScope,
        Instant resolvedAt
) {

    public ResolvedIdentity {
        effectiveCapabilities = effectiveCapabilities == null
                ? Set.of() : Set.copyOf(effectiveCapabilities);
    }

    /**
     * Whether the resolved identity still holds the given capability
     * (i.e. it survived the intersection).
     */
    public boolean allows(String capability) {
        return capability != null && effectiveCapabilities.contains(capability);
    }

    /**
     * The audit actor string: the service account, never the user.
     * Convention: {@code svc:<accountId>}.
     */
    public String actor() {
        return "svc:" + serviceAccountId;
    }

    /**
     * Whether this resolved identity may read the given memory namespace
     * (granted-only check in v1).
     */
    public boolean canReadMemoryScope(String scope) {
        return grantedScope.canReadMemoryScope(scope);
    }
}
