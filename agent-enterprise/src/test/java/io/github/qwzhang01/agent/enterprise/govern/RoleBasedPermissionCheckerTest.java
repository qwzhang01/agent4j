package io.github.qwzhang01.agent.enterprise.govern;

import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stage 15 M15.3: the role x tool permission matrix - redeeming the extension
 * point PermissionChecker's javadoc has reserved since Stage 9 ("user role X
 * can call tool Y").
 */
class RoleBasedPermissionCheckerTest {

    private static final String ROLE_CSR = "agent:csr";
    private static final String ROLE_SUPERVISOR = "supervisor";

    private ToolPolicy fallback;
    private Map<String, Set<String>> roleMatrix;

    @BeforeEach
    void setUp() {
        // the Stage 9 policy: what the world looks like WITHOUT any role grant
        fallback = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("refund_order", ToolPermission.REQUIRES_APPROVAL)
                .setPermission("delete_order", ToolPermission.DENY);

        roleMatrix = Map.of(
                ROLE_CSR, Set.of("search_knowledge", "query_order"),
                ROLE_SUPERVISOR, Set.of("query_order", "refund_order", "escalate_case"));
    }

    private RoleBasedPermissionChecker csr() {
        return RoleBasedPermissionChecker.forRequest(roleMatrix, fallback, Set.of(ROLE_CSR));
    }

    // ============ Blueprint Verification Case ============

    @Test
    @DisplayName("blueprint case: CSR gets query_order=AUTO, refund_order=REQUIRES_APPROVAL, delete_order=DENY")
    void blueprintThreeTierCase() {
        RoleBasedPermissionChecker checker = csr();
        assertEquals(ToolPermission.AUTO, checker.check("query_order"));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, checker.check("refund_order"));
        assertEquals(ToolPermission.DENY, checker.check("delete_order"));
    }

    // ============ Deny-First ============

    @Test
    @DisplayName("deny-first: a hard DENY in the fallback cannot be lifted by a role grant")
    void denyCannotBeLiftedByMatrix() {
        // a misconfigured (or malicious) matrix tries to grant delete_order to the CSR
        Map<String, Set<String>> overreaching = Map.of(
                ROLE_CSR, Set.of("query_order", "delete_order"));
        RoleBasedPermissionChecker checker =
                RoleBasedPermissionChecker.forRequest(overreaching, fallback, Set.of(ROLE_CSR));

        assertEquals(ToolPermission.DENY, checker.check("delete_order"),
                "the matrix restricts, it never overrides governance");
    }

    // ============ Multi-Role Union ============

    @Test
    @DisplayName("multiple roles union: any granted role makes the tool AUTO")
    void multiRoleUnion() {
        RoleBasedPermissionChecker csrAndSupervisor =
                RoleBasedPermissionChecker.forRequest(roleMatrix, fallback,
                        Set.of(ROLE_CSR, ROLE_SUPERVISOR));

        assertEquals(ToolPermission.AUTO, csrAndSupervisor.check("escalate_case"),
                "granted via supervisor matrix");
        assertEquals(ToolPermission.AUTO, csrAndSupervisor.check("search_knowledge"),
                "granted via CSR matrix");
    }

    @Test
    @DisplayName("supervisor role grants refund_order AUTO (matrix hit beats fallback)")
    void supervisorRefundAuto() {
        RoleBasedPermissionChecker supervisor =
                RoleBasedPermissionChecker.forRequest(roleMatrix, fallback, Set.of(ROLE_SUPERVISOR));

        assertEquals(ToolPermission.AUTO, supervisor.check("refund_order"),
                "the supervisor matrix explicitly grants what the fallback would gate");
    }

    // ============ No Roles / Unknown Tools ============

    @Test
    @DisplayName("no roles: everything falls back to the policy")
    void noRolesFallback() {
        RoleBasedPermissionChecker anonymous =
                RoleBasedPermissionChecker.forRequest(roleMatrix, fallback, Set.of());

        assertEquals(ToolPermission.AUTO, anonymous.check("query_order"));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, anonymous.check("refund_order"));
        assertEquals(ToolPermission.DENY, anonymous.check("delete_order"));
    }

    @Test
    @DisplayName("tool unknown to both matrix and explicit policy gets the policy default")
    void unknownToolUsesPolicyDefault() {
        RoleBasedPermissionChecker checker = csr();
        assertEquals(ToolPermission.AUTO, checker.check("some_new_tool"));
    }

    // ============ Accessors & Validation ============

    @Test
    @DisplayName("bound roles and matrix are visible for assembly/audit")
    void accessors() {
        RoleBasedPermissionChecker checker = csr();
        assertEquals(Set.of(ROLE_CSR), checker.boundRoles());
        assertEquals(Set.of("search_knowledge", "query_order"),
                checker.roleMatrix().get(ROLE_CSR));
    }

    @Test
    @DisplayName("factory rejects nulls")
    void factoryValidation() {
        assertThrows(NullPointerException.class,
                () -> RoleBasedPermissionChecker.forRequest(null, fallback, Set.of()));
        assertThrows(NullPointerException.class,
                () -> RoleBasedPermissionChecker.forRequest(roleMatrix, null, Set.of()));
        assertThrows(NullPointerException.class,
                () -> RoleBasedPermissionChecker.forRequest(roleMatrix, fallback, null));
    }
}
