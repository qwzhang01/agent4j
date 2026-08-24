package io.github.qwzhang01.agent.enterprise.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 15 M15.1: tenant/user domain and the login gate.
 * <p>
 * Fail-closed is the contract under test: every rejection path throws
 * {@link EnterpriseAuthException} with an evidence-carrying message -
 * there is no anonymous or degraded fallback.
 */
class TenantRegistryTest {

    private TenantRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();
        registry.registerTenant(Tenant.active("acme", "Acme Corp"));
        registry.registerTenant(Tenant.active("globex", "Globex Inc"));
        registry.registerUser(
                new User("u-alice", "acme", "Alice", Set.of(User.ROLE_CSR)), "key-alice");
        registry.registerUser(
                new User("u-bob", "acme", "Bob", Set.of(User.ROLE_SUPERVISOR)), "key-bob");
        registry.registerUser(
                new User("u-carol", "globex", "Carol", Set.of(User.ROLE_CSR)), "key-carol");
    }

    // ============ Login Success ============

    @Test
    @DisplayName("successful login produces a fully attributed RequestContext")
    void loginSuccess() {
        RequestContext ctx = registry.login("acme", "u-alice", "key-alice");

        assertEquals("acme", ctx.tenant().tenantId());
        assertEquals("u-alice", ctx.user().userId());
        assertEquals("acme", ctx.tenantId());
        assertEquals("u-alice", ctx.userId());
        assertFalse(ctx.sessionId().isBlank());
    }

    @Test
    @DisplayName("each login gets a fresh sessionId")
    void sessionIdsAreFresh() {
        RequestContext a = registry.login("acme", "u-alice", "key-alice");
        RequestContext b = registry.login("acme", "u-alice", "key-alice");
        assertNotEquals(a.sessionId(), b.sessionId());
    }

    @Test
    @DisplayName("memoryScopes() is the retrieval whitelist: tenant + user only")
    void memoryScopesWhitelist() {
        RequestContext ctx = registry.login("acme", "u-alice", "key-alice");
        assertEquals(java.util.List.of("tenant:acme", "user:u-alice"), ctx.memoryScopes());
    }

    @Test
    @DisplayName("actor() is the audit attribution string")
    void actorString() {
        RequestContext ctx = registry.login("acme", "u-alice", "key-alice");
        assertEquals("user:u-alice", ctx.actor());
    }

    // ============ Login Fail-Closed (five forms) ============

    @Test
    @DisplayName("unknown tenant fails closed")
    void unknownTenantRejected() {
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.login("initech", "u-alice", "key-alice"));
        assertTrue(ex.getMessage().contains("Unknown tenant"), ex.getMessage());
    }

    @Test
    @DisplayName("suspended tenant fails closed")
    void suspendedTenantRejected() {
        registry.suspendTenant("acme");
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.login("acme", "u-alice", "key-alice"));
        assertTrue(ex.getMessage().contains("SUSPENDED"), ex.getMessage());
    }

    @Test
    @DisplayName("suspending an unknown tenant fails closed")
    void suspendUnknownTenantRejected() {
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.suspendTenant("initech"));
        assertTrue(ex.getMessage().contains("Unknown tenant"), ex.getMessage());
    }

    @Test
    @DisplayName("unknown user fails closed")
    void unknownUserRejected() {
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.login("acme", "u-nobody", "key-alice"));
        assertTrue(ex.getMessage().contains("Unknown user"), ex.getMessage());
    }

    @Test
    @DisplayName("logging in under a tenant the user does not belong to fails closed")
    void tenantMismatchRejected() {
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.login("globex", "u-alice", "key-alice"));
        assertTrue(ex.getMessage().contains("does not belong to tenant 'globex'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("acme"), "evidence must name the home tenant");
    }

    @Test
    @DisplayName("wrong api key fails closed")
    void wrongKeyRejected() {
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.login("acme", "u-alice", "wrong-key"));
        assertTrue(ex.getMessage().contains("Invalid api key"), ex.getMessage());
    }

    // ============ Registration Fail-Closed ============

    @Test
    @DisplayName("registering a user under an unregistered tenant fails closed")
    void userOfUnregisteredTenantRejected() {
        User stranger = new User("u-stranger", "initech", "Stranger", Set.of());
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.registerUser(stranger, "key"));
        assertTrue(ex.getMessage().contains("home tenant 'initech' is not registered"), ex.getMessage());
    }

    @Test
    @DisplayName("registering a user under a suspended tenant fails closed")
    void userOfSuspendedTenantRejected() {
        registry.registerTenant(new Tenant("paused", "Paused LLC",
                Tenant.TenantStatus.SUSPENDED, Tenant.UNLIMITED_BUDGET));
        User stranger = new User("u-stranger", "paused", "Stranger", Set.of());
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.registerUser(stranger, "key"));
        assertTrue(ex.getMessage().contains("SUSPENDED"), ex.getMessage());
    }

    @Test
    @DisplayName("duplicate userId fails closed")
    void duplicateUserRejected() {
        User dup = new User("u-alice", "acme", "Alice Clone", Set.of());
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.registerUser(dup, "key"));
        assertTrue(ex.getMessage().contains("already registered"), ex.getMessage());
    }

    @Test
    @DisplayName("blank api key fails closed")
    void blankKeyRejected() {
        User fresh = new User("u-dave", "acme", "Dave", Set.of());
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.registerUser(fresh, "  "));
        assertTrue(ex.getMessage().contains("apiKey must not be blank"), ex.getMessage());
    }

    @Test
    @DisplayName("duplicate tenantId fails closed")
    void duplicateTenantRejected() {
        EnterpriseAuthException ex = assertThrows(EnterpriseAuthException.class,
                () -> registry.registerTenant(Tenant.active("acme", "Acme Again")));
        assertTrue(ex.getMessage().contains("Tenant already registered"), ex.getMessage());
    }

    // ============ Record Validation ============

    @Test
    @DisplayName("Tenant/User records reject blank identifiers")
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> Tenant.active("  ", "x"));
        assertThrows(IllegalArgumentException.class, () -> new User("", "acme", "X", Set.of()));
    }

    @Test
    @DisplayName("User with null roles defaults to empty role set")
    void nullRolesDefaulted() {
        User minimal = new User("u-min", "acme", "Min", null);
        assertTrue(minimal.roles().isEmpty());
        assertFalse(minimal.hasRole(User.ROLE_CSR));
    }

    @Test
    @DisplayName("RequestContext rejects null tenant/user")
    void contextValidation() {
        User u = new User("u-x", "acme", "X", Set.of());
        Tenant t = Tenant.active("acme", "Acme");
        assertThrows(NullPointerException.class, () -> new RequestContext(null, u, null));
        assertThrows(NullPointerException.class, () -> new RequestContext(t, null, null));
    }

    @Test
    @DisplayName("blank sessionId is auto-generated, explicit one preserved")
    void sessionIdHandling() {
        User u = new User("u-x", "acme", "X", Set.of());
        Tenant t = Tenant.active("acme", "Acme");
        RequestContext explicit = new RequestContext(t, u, "sess-42");
        RequestContext generated = new RequestContext(t, u, " ");
        assertEquals("sess-42", explicit.sessionId());
        assertTrue(generated.sessionId().startsWith("sess-"));
    }

    // ============ Lookups ============

    @Test
    @DisplayName("lookups return entities but never credentials")
    void lookups() {
        assertTrue(registry.findTenant("acme").isPresent());
        assertTrue(registry.findTenant("initech").isEmpty());
        assertTrue(registry.findUser("u-alice").isPresent());
        assertTrue(registry.findUser("u-nobody").isEmpty());
        assertEquals(Set.of(User.ROLE_CSR), registry.findUser("u-alice").get().roles());
    }
}
