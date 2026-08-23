package io.github.qwzhang01.agent.product.prompt;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.4 manager tests: append-only history, channel routing, tenant overrides,
 * pointer rollback.
 */
class PromptManagerTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC);

    private final PromptManager manager = new PromptManager(FIXED);

    // ============ Publish ============

    @Test
    void publishAppendsMonotonicVersions() {
        PromptVersion v1 = manager.publish("support-system", "v1 content");
        PromptVersion v2 = manager.publish("support-system", "v2 content");

        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertEquals("stable", v1.channel());
        assertEquals(2, manager.history("support-system").size());
        assertEquals(FIXED.instant(), v1.publishedAt());
    }

    @Test
    void canaryPublishDoesNotMoveStable() {
        manager.publish("p", "stable-1");
        manager.publish("p", "canary-1", PromptChannel.CANARY);

        assertEquals("stable-1", manager.resolve("p", null, null).orElseThrow().content());
        assertEquals("canary-1",
                manager.resolve("p", null, PromptChannel.CANARY).orElseThrow().content());
    }

    @Test
    void invalidChannelIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.publish("p", "x", "beta"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.publish(null, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.publish("p", null));
    }

    // ============ Resolve (routing) ============

    @Test
    void defaultResolveReturnsStable() {
        manager.publish("p", "stable-1");
        manager.publish("p", "stable-2");     // stable pointer now at v2

        assertEquals("stable-2", manager.resolve("p", null, null).orElseThrow().content());
    }

    @Test
    void unknownPromptResolvesEmpty() {
        assertTrue(manager.resolve("ghost", null, null).isEmpty());
    }

    @Test
    void channelWithoutVersionResolvesEmpty() {
        manager.publish("p", "stable-1");
        assertTrue(manager.resolve("p", null, PromptChannel.CANARY).isEmpty());
    }

    @Test
    void tenantOverrideBeatsDeclaredChannel() {
        manager.publish("p", "stable-1");
        manager.publish("p", "canary-1", PromptChannel.CANARY);

        manager.setTenantChannel("acme", "p", PromptChannel.CANARY);

        // acme is routed to canary even though the caller declared nothing.
        assertEquals("canary-1", manager.resolve("p", "acme", null).orElseThrow().content());
        // Other tenants still see stable.
        assertEquals("stable-1", manager.resolve("p", "other", null).orElseThrow().content());
    }

    @Test
    void clearedTenantOverrideFallsBack() {
        manager.publish("p", "stable-1");
        manager.publish("p", "canary-1", PromptChannel.CANARY);
        manager.setTenantChannel("acme", "p", PromptChannel.CANARY);

        manager.clearTenantChannel("acme", "p");

        assertEquals("stable-1", manager.resolve("p", "acme", null).orElseThrow().content());
    }

    // ============ Rollback ============

    @Test
    void rollbackMovesStablePointerOneStepBack() {
        manager.publish("p", "v1");
        manager.publish("p", "v2");
        manager.publish("p", "v3");

        PromptVersion rolledBack = manager.rollback("p");

        assertEquals(2, rolledBack.version());
        assertEquals("v2", manager.resolve("p", null, null).orElseThrow().content());
        // History untouched - v3 is still there.
        assertEquals(3, manager.history("p").size());
    }

    @Test
    void rollbackSkipsCanaryVersions() {
        manager.publish("p", "v1");
        manager.publish("p", "v2");
        manager.publish("p", "canary-exp", PromptChannel.CANARY);   // v3, canary
        manager.publish("p", "v4");

        // Stable pointer at v4; rolling back must find v2, NOT the canary v3.
        PromptVersion rolledBack = manager.rollback("p");

        assertEquals(2, rolledBack.version());
        assertEquals("canary-exp",
                manager.resolve("p", null, PromptChannel.CANARY).orElseThrow().content(),
                "canary pointer unaffected by stable rollback");
    }

    @Test
    void rollbackAtEarliestVersionIsRejected() {
        manager.publish("p", "only");
        assertThrows(IllegalArgumentException.class, () -> manager.rollback("p"));
    }

    @Test
    void rollbackUnknownPromptIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.rollback("ghost"));
    }

    // ============ History discipline ============

    @Test
    void historyIsImmutable() {
        manager.publish("p", "v1");
        assertThrows(UnsupportedOperationException.class,
                () -> manager.history("p").add(
                        new PromptVersion("p", 99, "x", "stable", FIXED.instant())));
    }

    @Test
    void historyContentIsNeverRewrittenByRollback() {
        manager.publish("p", "v1");
        manager.publish("p", "v2");
        manager.rollback("p");

        assertEquals("v2", manager.history("p").get(1).content(),
                "stored content must stay exactly as published");
        assertNotEquals(manager.history("p").get(1).content(),
                manager.resolve("p", null, null).orElseThrow().content());
    }

    @Test
    void promptNamesListsAllManagedPrompts() {
        manager.publish("a", "x");
        manager.publish("b", "y");
        assertEquals(java.util.Set.of("a", "b"), manager.promptNames());
    }
}
