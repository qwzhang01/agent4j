package io.github.qwzhang01.agent.channel.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ServiceAccount} (Stage 12 M12.1).
 */
class ServiceAccountTest {

    private static AgentIdentity identity() {
        return new AgentIdentity("eng-bot", "Engineering Bot", "team-eng-leads");
    }

    // ============ Validation ============

    @Test
    @DisplayName("blank accountId is rejected")
    void validation_blankAccountId() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceAccount.of(" ", identity(), IdentityScope.empty()));
    }

    @Test
    @DisplayName("validFrom >= validUntil is a configuration error and rejected")
    void validation_invertedWindow() {
        Instant t = Instant.parse("2026-08-22T00:00:00Z");
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceAccount("svc-1", identity(), IdentityScope.empty(),
                        ServiceAccount.UNLIMITED_BUDGET, t, t));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceAccount("svc-1", identity(), IdentityScope.empty(),
                        ServiceAccount.UNLIMITED_BUDGET, t.plusSeconds(1), t));
    }

    // ============ Validity window ============

    @Test
    @DisplayName("open-ended window (null bounds) is valid at any time")
    void validity_openEnded() {
        ServiceAccount account = ServiceAccount.of("svc-1", identity(), IdentityScope.empty());
        assertTrue(account.isValidAt(Instant.parse("2020-01-01T00:00:00Z")));
        assertTrue(account.isValidAt(Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    @DisplayName("bounded window: valid inside, expired at/after validUntil, not yet valid before validFrom")
    void validity_bounded() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant until = Instant.parse("2026-09-01T00:00:00Z");
        ServiceAccount account = new ServiceAccount("svc-1", identity(), IdentityScope.empty(),
                ServiceAccount.UNLIMITED_BUDGET, from, until);

        assertTrue(account.isValidAt(from.plusSeconds(1)), "inside the window: valid");
        assertFalse(account.isValidAt(from.minusSeconds(1)), "before validFrom: not yet valid");
        assertFalse(account.isValidAt(until), "validUntil is exclusive: expired at the boundary");
        assertTrue(account.isValidAt(until.minusSeconds(1)), "last instant inside the window");
    }

    // ============ Budget placeholder ============

    @Test
    @DisplayName("budget placeholder: of() defaults to unlimited, hasBudgetCap() flips when set")
    void budget_placeholder() {
        ServiceAccount unlimited = ServiceAccount.of("svc-1", identity(), IdentityScope.empty());
        assertFalse(unlimited.hasBudgetCap());
        assertEquals(-1L, unlimited.monthlyTokenBudget());

        ServiceAccount capped = new ServiceAccount("svc-2", identity(), IdentityScope.empty(),
                100_000L, null, null);
        assertTrue(capped.hasBudgetCap());
        assertEquals(100_000L, capped.monthlyTokenBudget());
    }
}
