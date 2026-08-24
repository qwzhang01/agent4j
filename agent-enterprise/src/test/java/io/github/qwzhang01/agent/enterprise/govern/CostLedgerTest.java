package io.github.qwzhang01.agent.enterprise.govern;

import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.enterprise.tenant.Tenant;
import io.github.qwzhang01.agent.enterprise.tenant.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 15 M15.3: the two-level cost ledger - pre-gate fail-closed,
 * post-recording accumulate, dimensions independent.
 */
class CostLedgerTest {

    private Tenant acmeCapped;      // tenant budget = 1000
    private Tenant globexUnlimited;
    private RequestContext alice;   // acme user, personal limit = 200
    private RequestContext bob;     // acme user, no personal limit
    private RequestContext carol;   // globex user

    @BeforeEach
    void setUp() {
        acmeCapped = new Tenant("acme", "Acme", Tenant.TenantStatus.ACTIVE, 1000);
        globexUnlimited = Tenant.active("globex", "Globex");
        alice = new RequestContext(acmeCapped,
                new User("u-alice", "acme", "Alice", Set.of()), null);
        bob = new RequestContext(acmeCapped,
                new User("u-bob", "acme", "Bob", Set.of()), null);
        carol = new RequestContext(globexUnlimited,
                new User("u-carol", "globex", "Carol", Set.of()), null);
    }

    private CostLedger ledger() {
        return new CostLedger(Map.of("u-alice", 200L));
    }

    // ============ Pre-Gate ============

    @Test
    @DisplayName("within budget: the gate passes silently")
    void withinBudgetPasses() {
        CostLedger ledger = ledger();
        assertDoesNotThrow(() -> ledger.requireBudget(alice));
        assertDoesNotThrow(() -> ledger.requireBudget(carol));
    }

    @Test
    @DisplayName("tenant budget exhausted: fail-closed with evidence")
    void tenantBudgetExhausted() {
        CostLedger ledger = ledger();
        ledger.record(alice, 600, 400);   // exactly 1000 = the cap

        BudgetExceededException ex = assertThrows(BudgetExceededException.class,
                () -> ledger.requireBudget(bob));
        assertEquals(BudgetExceededException.Dimension.TENANT, ex.dimension());
        assertEquals(1000, ex.used());
        assertEquals(1000, ex.limit());
        assertTrue(ex.getMessage().contains("acme"), ex.getMessage());
    }

    @Test
    @DisplayName("user budget exhausted independently of the tenant budget")
    void userBudgetExhausted() {
        CostLedger ledger = ledger();
        ledger.record(alice, 120, 80);    // alice: 200 = her personal cap; tenant far below 1000

        BudgetExceededException ex = assertThrows(BudgetExceededException.class,
                () -> ledger.requireBudget(alice));
        assertEquals(BudgetExceededException.Dimension.USER, ex.dimension());
        assertEquals(200, ex.used());
        assertEquals(200, ex.limit());
        // bob is unaffected - his dimension is independent
        assertDoesNotThrow(() -> ledger.requireBudget(bob));
    }

    @Test
    @DisplayName("unlimited tenant and unlisted users never throw")
    void unlimitedNeverThrows() {
        CostLedger ledger = ledger();
        ledger.record(carol, Long.MAX_VALUE / 2, 0);
        assertDoesNotThrow(() -> ledger.requireBudget(carol));
    }

    // ============ Post-Recording ============

    @Test
    @DisplayName("record accumulates prompt+completion into both dimensions")
    void recordAccumulates() {
        CostLedger ledger = ledger();
        ledger.record(alice, 100, 50);
        ledger.record(alice, 200, 100);
        ledger.record(bob, 10, 5);

        assertEquals(465, ledger.tenantUsed("acme"), "tenant bills both users: 450 + 15");
        assertEquals(450, ledger.userUsed("u-alice"), "150 + 300");
        assertEquals(15, ledger.userUsed("u-bob"));
    }

    @Test
    @DisplayName("record after recording flips the gate: 199 used passes, +1 more blocks")
    void gateFlipsAfterRecording() {
        CostLedger ledger = ledger();
        ledger.record(alice, 150, 49);    // alice at 199 of 200

        assertDoesNotThrow(() -> ledger.requireBudget(alice));
        ledger.record(alice, 1, 0);       // now exactly 200
        assertThrows(BudgetExceededException.class, () -> ledger.requireBudget(alice));
    }

    @Test
    @DisplayName("recording rejects negative counts")
    void recordValidation() {
        CostLedger ledger = ledger();
        assertThrows(IllegalArgumentException.class, () -> ledger.record(alice, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.record(alice, 0, -5));
        assertThrows(NullPointerException.class, () -> ledger.record(null, 1, 1));
    }

    // ============ Dimensions Independent ============

    @Test
    @DisplayName("tenants bill independently: globex usage never counts toward acme")
    void tenantsIndependent() {
        CostLedger ledger = ledger();
        ledger.record(alice, 500, 500);   // acme at its cap
        ledger.record(carol, 100, 100);   // globex usage

        assertEquals(1000, ledger.tenantUsed("acme"));
        assertEquals(200, ledger.tenantUsed("globex"));
        assertDoesNotThrow(() -> ledger.requireBudget(carol),
                "globex has no budget and its own usage");
    }

    @Test
    @DisplayName("tenantOnly ledger: no user dimension, but the tenant gate still applies")
    void tenantOnlyLedger() {
        CostLedger ledger = CostLedger.tenantOnly();
        // heavy personal usage, no user limit configured -> user dimension absent
        ledger.record(alice, 900, 0);
        assertDoesNotThrow(() -> ledger.requireBudget(alice),
                "no user limits configured -> only the tenant gate applies (900 < 1000)");
        // but the tenant gate still closes at the cap
        ledger.record(alice, 100, 0);   // tenant now at exactly 1000
        BudgetExceededException ex = assertThrows(BudgetExceededException.class,
                () -> ledger.requireBudget(bob));
        assertEquals(BudgetExceededException.Dimension.TENANT, ex.dimension());
    }
}
