package io.github.qwzhang01.agent.channel.identity;

import java.time.Instant;
import java.util.Set;

/**
 * The outcome of one identity resolution attempt (Stage 12 D4/D6).
 * <p>
 * Both allowed AND denied resolutions produce a decision, and every
 * decision is offered to the audit sink - "who tried to act under which
 * agent identity but was blocked" is itself a security signal (same
 * philosophy as Stage 9 D6: denied is intelligence, not noise).
 * <p>
 * This type deliberately mirrors Stage 9's AuditEvent shape (status +
 * reason + context) without depending on it: the assembly layer bridges
 * decisions into the AuditLogger, keeping the module boundary discipline
 * established by agent-orchestrator's D5.
 *
 * @param channelId  the channel where the resolution was requested
 * @param userId     the invoking user
 * @param agentId    the agent being invoked
 * @param allowed    whether the resolution succeeded
 * @param reason     denial reason, null when allowed
 * @param granted    the agent's granted capabilities (decision context for auditing)
 * @param role       the user's channel role capabilities (decision context for auditing)
 * @param timestamp  when the decision was made
 */
public record IdentityDecision(
        String channelId,
        String userId,
        String agentId,
        boolean allowed,
        DenialReason reason,
        Set<String> granted,
        Set<String> role,
        Instant timestamp
) {

    /** Why a resolution failed closed. */
    public enum DenialReason {
        /** No service account registered for this agentId. */
        UNKNOWN_AGENT,
        /** The account's validity window has not started. */
        ACCOUNT_NOT_YET_VALID,
        /** The account's validity window has ended. */
        ACCOUNT_EXPIRED,
        /** The user is not a member of the channel (provider returned null). */
        USER_NOT_IN_CHANNEL,
        /** Granted INTERSECT role capabilities is empty - nothing to run under. */
        EMPTY_PERMISSION_INTERSECTION
    }

    public IdentityDecision {
        Set<String> g = granted == null ? Set.of() : Set.copyOf(granted);
        Set<String> r = role == null ? Set.of() : Set.copyOf(role);
        granted = g;
        role = r;
    }

    // ============ Factory Methods ============

    static IdentityDecision allowed(String channelId, String userId, String agentId,
                                    Set<String> granted, Set<String> role) {
        return new IdentityDecision(channelId, userId, agentId, true, null, granted, role, Instant.now());
    }

    static IdentityDecision denied(String channelId, String userId, String agentId,
                                   DenialReason reason, Set<String> granted, Set<String> role) {
        return new IdentityDecision(channelId, userId, agentId, false, reason, granted, role, Instant.now());
    }
}
