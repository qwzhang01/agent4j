package io.github.qwzhang01.agent.channel.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IdentityResolver} (Stage 12 M12.1).
 * <p>
 * Covers the three-party resolution contract:
 * effective capabilities = granted INTERSECT role, fail-closed on five
 * denial conditions, every decision offered to the audit sink.
 */
class IdentityResolverTest {

    // ============ Fixtures ============

    private static final String CHANNEL = "team-eng";
    private static final String USER_A = "user-a";

    private static ServiceAccount engBotAccount(IdentityScope scope) {
        return ServiceAccount.of("svc-eng-bot-01",
                new AgentIdentity("eng-bot", "Engineering Bot", "team-eng-leads"), scope);
    }

    private static ServiceAccount engBotAccount(IdentityScope scope, Instant from, Instant until) {
        return new ServiceAccount("svc-eng-bot-01",
                new AgentIdentity("eng-bot", "Engineering Bot", "team-eng-leads"),
                scope, ServiceAccount.UNLIMITED_BUDGET, from, until);
    }

    /** Simple map-backed role provider: (channel, user) -> caps; null = not a member. */
    private static final class MapRoles implements ChannelRolePermissions {
        final Map<String, Set<String>> byChannelUser = new ConcurrentHashMap<>();

        MapRoles grant(String channel, String user, Set<String> caps) {
            byChannelUser.put(channel + "/" + user, caps);
            return this;
        }

        @Override
        public Set<String> capabilities(String channelId, String userId) {
            return byChannelUser.get(channelId + "/" + userId);
        }
    }

    // ============ Happy path + intersection semantics ============

    @Test
    @DisplayName("resolve succeeds: effective capabilities are the intersection, never the union")
    void resolve_intersectionSemantics() {
        MapRoles roles = new MapRoles().grant(CHANNEL, USER_A,
                Set.of("git.read", "calendar.read"));
        IdentityResolver resolver = new IdentityResolver(roles, null);
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read", "ci.trigger")));

        ResolvedIdentity resolved = resolver.resolve(CHANNEL, USER_A, "eng-bot");

        assertEquals(Set.of("git.read"), resolved.effectiveCapabilities(),
                "only git.read is on both sides; union would wrongly include calendar.read + ci.trigger");
        assertEquals("eng-bot", resolved.identity().agentId());
        assertEquals("svc-eng-bot-01", resolved.serviceAccountId());
        assertEquals(CHANNEL, resolved.channelId());
        assertEquals(USER_A, resolved.userId());
        assertNotNull(resolved.resolvedAt());
    }

    @Test
    @DisplayName("resolved identity helpers: allows / actor / canReadMemoryScope")
    void resolve_helpers() {
        MapRoles roles = new MapRoles().grant(CHANNEL, USER_A, Set.of("git.read", "crm.read"));
        IdentityResolver resolver = new IdentityResolver(roles, null);
        resolver.register(engBotAccount(new IdentityScope(
                Set.of("git.read", "crm.read"),
                Set.of("channel:team-eng", "agent:eng-bot"),
                Set.of("internal"))));

        ResolvedIdentity resolved = resolver.resolve(CHANNEL, USER_A, "eng-bot");

        assertTrue(resolved.allows("git.read"));
        assertFalse(resolved.allows("ci.trigger"), "granted but not in the intersection");
        assertEquals("svc:svc-eng-bot-01", resolved.actor(),
                "the audit actor is the service account, never the user");
        assertTrue(resolved.canReadMemoryScope("channel:team-eng"));
        assertFalse(resolved.canReadMemoryScope("channel:sales"));
    }

    @Test
    @DisplayName("identity isolation: two agents with different scopes never see each other's capabilities")
    void resolve_identityIsolation() {
        MapRoles roles = new MapRoles()
                .grant("sales", "u1", Set.of("crm.read", "calendar.read"))
                .grant("eng", "u2", Set.of("git.read", "ci.trigger"));
        IdentityResolver resolver = new IdentityResolver(roles, null);
        resolver.register(ServiceAccount.of("svc-sales-01",
                new AgentIdentity("sales-bot", "Sales Bot", "sales-ops"),
                new IdentityScope(Set.of("crm.read", "calendar.read"), Set.of("channel:sales"), Set.of())));
        resolver.register(engBotAccount(new IdentityScope(
                Set.of("git.read", "ci.trigger"), Set.of("channel:eng"), Set.of())));

        ResolvedIdentity sales = resolver.resolve("sales", "u1", "sales-bot");
        ResolvedIdentity eng = resolver.resolve("eng", "u2", "eng-bot");

        assertTrue(sales.allows("crm.read"));
        assertFalse(sales.allows("git.read"), "sales agent must not reach engineering capabilities");
        assertTrue(eng.allows("git.read"));
        assertFalse(eng.allows("crm.read"), "engineering agent must not reach sales capabilities");
        assertFalse(sales.canReadMemoryScope("channel:eng"), "scope isolation: no cross-channel memory");
        assertFalse(eng.canReadMemoryScope("channel:sales"));
    }

    // ============ Fail-closed denials ============

    @Test
    @DisplayName("UNKNOWN_AGENT: unregistered agentId is denied with the decision attached")
    void deny_unknownAgent() {
        List<IdentityDecision> audit = new ArrayList<>();
        IdentityResolver resolver = new IdentityResolver(new MapRoles(), audit::add);

        IdentityResolutionException ex = assertThrows(IdentityResolutionException.class,
                () -> resolver.resolve(CHANNEL, USER_A, "ghost-bot"));

        assertEquals(IdentityDecision.DenialReason.UNKNOWN_AGENT, ex.reason());
        assertEquals("ghost-bot", ex.decision().agentId());
        assertFalse(ex.decision().allowed());
    }

    @Test
    @DisplayName("ACCOUNT_EXPIRED: an account past validUntil is denied")
    void deny_expiredAccount() {
        IdentityResolver resolver = new IdentityResolver(
                new MapRoles().grant(CHANNEL, USER_A, Set.of("git.read")), null);
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read"),
                Instant.parse("2026-08-21T00:00:00Z"),   // from yesterday
                Instant.parse("2026-08-22T00:00:00Z"))); // until today 00:00 -> already past

        IdentityResolutionException ex = assertThrows(IdentityResolutionException.class,
                () -> resolver.resolve(CHANNEL, USER_A, "eng-bot"));
        assertEquals(IdentityDecision.DenialReason.ACCOUNT_EXPIRED, ex.reason());
    }

    @Test
    @DisplayName("ACCOUNT_NOT_YET_VALID: an account before validFrom is denied")
    void deny_notYetValid() {
        IdentityResolver resolver = new IdentityResolver(
                new MapRoles().grant(CHANNEL, USER_A, Set.of("git.read")), null);
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read"),
                Instant.now().plusSeconds(3600),   // starts in an hour
                null));

        IdentityResolutionException ex = assertThrows(IdentityResolutionException.class,
                () -> resolver.resolve(CHANNEL, USER_A, "eng-bot"));
        assertEquals(IdentityDecision.DenialReason.ACCOUNT_NOT_YET_VALID, ex.reason());
    }

    @Test
    @DisplayName("USER_NOT_IN_CHANNEL: a non-member (provider returns null) is denied even with a valid account")
    void deny_userNotInChannel() {
        IdentityResolver resolver = new IdentityResolver(new MapRoles(), null);
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read")));

        IdentityResolutionException ex = assertThrows(IdentityResolutionException.class,
                () -> resolver.resolve(CHANNEL, "stranger", "eng-bot"));
        assertEquals(IdentityDecision.DenialReason.USER_NOT_IN_CHANNEL, ex.reason());
    }

    @Test
    @DisplayName("EMPTY_PERMISSION_INTERSECTION: member with disjoint role capabilities is denied")
    void deny_emptyIntersection() {
        IdentityResolver resolver = new IdentityResolver(
                new MapRoles().grant(CHANNEL, USER_A, Set.of("calendar.read")), null);
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read", "ci.trigger")));

        IdentityResolutionException ex = assertThrows(IdentityResolutionException.class,
                () -> resolver.resolve(CHANNEL, USER_A, "eng-bot"));

        assertEquals(IdentityDecision.DenialReason.EMPTY_PERMISSION_INTERSECTION, ex.reason());
        assertEquals(Set.of("git.read", "ci.trigger"), ex.decision().granted(),
                "decision carries both permission sets: why it was denied is auditable");
        assertEquals(Set.of("calendar.read"), ex.decision().role());
    }

    // ============ Audit sink ============

    @Test
    @DisplayName("audit sink receives BOTH allowed and denied decisions (denied is intelligence)")
    void audit_bothOutcomesEmitted() {
        List<IdentityDecision> audit = new ArrayList<>();
        MapRoles roles = new MapRoles().grant(CHANNEL, USER_A, Set.of("git.read"));
        IdentityResolver resolver = new IdentityResolver(roles, audit::add);
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read")));

        resolver.resolve(CHANNEL, USER_A, "eng-bot");                       // allowed
        assertThrows(IdentityResolutionException.class,
                () -> resolver.resolve(CHANNEL, "stranger", "eng-bot"));    // denied

        assertEquals(2, audit.size());
        assertTrue(audit.get(0).allowed());
        assertEquals(CHANNEL, audit.get(0).channelId());
        assertEquals(USER_A, audit.get(0).userId());
        assertFalse(audit.get(1).allowed());
        assertEquals(IdentityDecision.DenialReason.USER_NOT_IN_CHANNEL, audit.get(1).reason());
    }

    @Test
    @DisplayName("a throwing audit sink never changes resolution semantics")
    void audit_brokenSinkSwallowed() {
        IdentityResolver resolver = new IdentityResolver(
                (channelId, userId) -> null,                                  // no members
                decision -> { throw new IllegalStateException("sink down"); }); // broken sink
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read")));

        // Resolution proceeds regardless of the broken sink...
        // (this resolver has no members, so it denies - the point is the sink
        // exception must not mask or replace the denial reason)
        IdentityResolutionException ex = assertThrows(IdentityResolutionException.class,
                () -> resolver.resolve(CHANNEL, USER_A, "eng-bot"));
        assertEquals(IdentityDecision.DenialReason.USER_NOT_IN_CHANNEL, ex.reason());
    }

    // ============ Registration ============

    @Test
    @DisplayName("double registration of the same agentId fails loudly")
    void registration_duplicateRejected() {
        IdentityResolver resolver = new IdentityResolver(new MapRoles(), null);
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read")));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.register(engBotAccount(IdentityScope.capabilities("crm.read"))));
        assertEquals(Set.of("eng-bot"), resolver.registeredAgents());
    }

    @Test
    @DisplayName("null arguments to resolve fail fast with NPE, not a denial")
    void resolve_nullArguments() {
        IdentityResolver resolver = new IdentityResolver(new MapRoles(), null);
        resolver.register(engBotAccount(IdentityScope.capabilities("git.read")));

        assertThrows(NullPointerException.class, () -> resolver.resolve(null, USER_A, "eng-bot"));
        assertThrows(NullPointerException.class, () -> resolver.resolve(CHANNEL, null, "eng-bot"));
        assertThrows(NullPointerException.class, () -> resolver.resolve(CHANNEL, USER_A, null));
    }
}
