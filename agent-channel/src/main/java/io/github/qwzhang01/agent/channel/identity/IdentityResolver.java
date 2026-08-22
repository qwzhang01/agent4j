package io.github.qwzhang01.agent.channel.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Resolves the three-party identity of a channel run (Stage 12 D4).
 * <p>
 * Every channel run carries an identity triple:
 * <pre>
 *   channelId (where) + userId (who invoked) + agentId (which agent)
 * </pre>
 * The resolution rule:
 * <pre>
 *   effective capabilities = agent's granted scope  INTERSECT
 *                            user's channel role capabilities
 * </pre>
 * never the union, never the user's full permissions. Fail-closed on five
 * conditions (see {@link IdentityDecision.DenialReason}); each denial also
 * produces a decision that is offered to the audit sink.
 * <p>
 * Design decision (module boundary, same discipline as orchestrator D5):
 * this class does NOT depend on agent-security. Audit integration is a
 * {@code Consumer<IdentityDecision>} sink that the assembly layer bridges
 * to Stage 9's AuditLogger; a null sink means "no audit" (tests may inject
 * a collector).
 * <p>
 * v1 honest boundary: memory scopes and data classifications are
 * granted-only (the user side has no corresponding input yet); only
 * capabilities go through the intersection.
 */
public class IdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(IdentityResolver.class);

    private final Map<String, ServiceAccount> accountsByAgentId = new ConcurrentHashMap<>();
    private final ChannelRolePermissions rolePermissions;
    private final Consumer<IdentityDecision> auditSink;

    /**
     * @param rolePermissions user-side capabilities provider (required)
     * @param auditSink       optional decision sink; null = no auditing
     */
    public IdentityResolver(ChannelRolePermissions rolePermissions, Consumer<IdentityDecision> auditSink) {
        this.rolePermissions = Objects.requireNonNull(rolePermissions, "rolePermissions must not be null");
        this.auditSink = auditSink;
    }

    // ============ Registration ============

    /**
     * Register a service account. One agentId maps to one active account;
     * double registration is a configuration error and fails loudly.
     */
    public IdentityResolver register(ServiceAccount account) {
        Objects.requireNonNull(account, "account must not be null");
        ServiceAccount prev = accountsByAgentId.putIfAbsent(account.identity().agentId(), account);
        if (prev != null) {
            throw new IllegalArgumentException(
                    "agentId already registered: " + account.identity().agentId()
                            + " (existing account: " + prev.accountId() + ")");
        }
        log.info("[identity] Registered service account '{}' for agent '{}'",
                account.accountId(), account.identity().agentId());
        return this;
    }

    // ============ Resolution ============

    /**
     * Resolve the effective identity for a channel run, or fail closed.
     *
     * @throws IdentityResolutionException on any denial condition; the
     *         decision (with reason and both permission sets) is attached
     *         and offered to the audit sink before throwing
     */
    public ResolvedIdentity resolve(String channelId, String userId, String agentId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");

        ServiceAccount account = accountsByAgentId.get(agentId);
        if (account == null) {
            return deny(channelId, userId, agentId, IdentityDecision.DenialReason.UNKNOWN_AGENT,
                    Set.of(), Set.of());
        }

        Set<String> granted = account.grantedScope().capabilities();

        java.time.Instant now = java.time.Instant.now();
        if (account.validFrom() != null && now.isBefore(account.validFrom())) {
            return deny(channelId, userId, agentId, IdentityDecision.DenialReason.ACCOUNT_NOT_YET_VALID,
                    granted, Set.of());
        }
        if (account.validUntil() != null && !now.isBefore(account.validUntil())) {
            return deny(channelId, userId, agentId, IdentityDecision.DenialReason.ACCOUNT_EXPIRED,
                    granted, Set.of());
        }

        Set<String> role = rolePermissions.capabilities(channelId, userId);
        if (role == null) {
            return deny(channelId, userId, agentId, IdentityDecision.DenialReason.USER_NOT_IN_CHANNEL,
                    granted, Set.of());
        }

        Set<String> effective = intersect(granted, role);
        if (effective.isEmpty()) {
            return deny(channelId, userId, agentId, IdentityDecision.DenialReason.EMPTY_PERMISSION_INTERSECTION,
                    granted, role);
        }

        ResolvedIdentity resolved = new ResolvedIdentity(
                channelId, userId, account.identity(), account.accountId(),
                effective, account.grantedScope(), now);
        emit(IdentityDecision.allowed(channelId, userId, agentId, granted, role));
        log.info("[identity] Resolved '{}' for user '{}' in channel '{}': capabilities={} actor={}",
                agentId, userId, channelId, effective, resolved.actor());
        return resolved;
    }

    /**
     * Registered agent ids (for diagnostics / M12.2 channel setup).
     */
    public Set<String> registeredAgents() {
        return Set.copyOf(accountsByAgentId.keySet());
    }

    // ============ Internals ============

    private ResolvedIdentity deny(String channelId, String userId, String agentId,
                                  IdentityDecision.DenialReason reason,
                                  Set<String> granted, Set<String> role) {
        IdentityDecision decision = IdentityDecision.denied(channelId, userId, agentId, reason, granted, role);
        emit(decision);
        log.warn("[identity] Denied agent='{}' user='{}' channel='{}' reason={}",
                agentId, userId, channelId, reason);
        throw new IdentityResolutionException(decision);
    }

    private void emit(IdentityDecision decision) {
        if (auditSink != null) {
            try {
                auditSink.accept(decision);
            } catch (Exception e) {
                // Auditing must never break resolution semantics; a broken
                // sink is logged and swallowed (the decision itself is the
                // source of truth in the exception / return value).
                log.error("[identity] Audit sink threw; decision={}", decision, e);
            }
        }
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).collect(Collectors.toUnmodifiableSet());
    }
}
