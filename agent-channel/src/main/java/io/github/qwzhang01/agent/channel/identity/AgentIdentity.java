package io.github.qwzhang01.agent.channel.identity;

import java.util.Objects;

/**
 * An agent's organizational service identity (Stage 12 D4).
 * <p>
 * The agent acts under THIS identity, never under the invoking user's
 * account. Identity borrowing ("on-behalf-of") is the anti-pattern this
 * class exists to prevent: when an agent uses a user's token, its audit
 * trail says "the user did it", and its permissions silently become the
 * user's permissions (privilege creep).
 * <p>
 * Identity isolation: different-purpose agents hold different identities -
 * a sales agent and an engineering agent never share an account, so their
 * reachable resources and memories never leak to each other.
 *
 * @param agentId     stable identifier, e.g. "eng-bot" (also the routing key in IdentityResolver)
 * @param displayName human-readable name shown in the channel, e.g. "Engineering Bot"
 * @param ownerId     who is responsible for this agent (team/on-call), e.g. "team-eng-leads"
 */
public record AgentIdentity(String agentId, String displayName, String ownerId) {

    public AgentIdentity {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        if (agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
    }
}
