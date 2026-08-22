package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Identity capabilities ∩ ToolPolicy (Stage 12 assembly bridge).
 */
class IdentityConstrainedPermissionCheckerTest {

    @Test
    void unbound_followsOriginalPolicy() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL)
                .setPermission("forbidden", ToolPermission.DENY);
        IdentityConstrainedPermissionChecker checker = new IdentityConstrainedPermissionChecker(policy);

        assertEquals(ToolPermission.AUTO, checker.check("echo"));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, checker.check("delete_file"));
        assertEquals(ToolPermission.DENY, checker.check("forbidden"));
    }

    @Test
    void missingCapability_isDenyEvenIfPolicyIsAuto() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        IdentityConstrainedPermissionChecker checker = new IdentityConstrainedPermissionChecker(policy);
        checker.bindCapabilities(Set.of("echo"));

        assertEquals(ToolPermission.DENY, checker.check("delete_file"));
        assertTrue(checker.isDenied("delete_file"));
    }

    @Test
    void grantedCapability_keepsOriginalTier() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("echo", ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL)
                .setPermission("forbidden", ToolPermission.DENY);
        IdentityConstrainedPermissionChecker checker = new IdentityConstrainedPermissionChecker(policy);
        checker.bindCapabilities(Set.of("echo", "delete_file", "forbidden"));

        assertEquals(ToolPermission.AUTO, checker.check("echo"));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, checker.check("delete_file"));
        assertEquals(ToolPermission.DENY, checker.check("forbidden"));
    }

    @Test
    void emptyBind_deniesEverything() {
        IdentityConstrainedPermissionChecker checker =
                new IdentityConstrainedPermissionChecker(new ToolPolicy(ToolPermission.AUTO));
        checker.bindCapabilities(Set.of());
        assertEquals(ToolPermission.DENY, checker.check("echo"));
    }

    @Test
    void governedExecutor_rejectsToolOutsideIdentity() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echo("echo"));
        registry.register(echo("delete_file"));

        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL);
        IdentityConstrainedPermissionChecker checker = new IdentityConstrainedPermissionChecker(policy);
        checker.bindCapabilities(Set.of("echo"));

        GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(checker)
                .approvalService(ConsoleApprovalService.autoApprove())
                .build();

        String denied = executor.execute(ToolCall.of("1", "delete_file", "{\"input\":\"x\"}"));
        assertTrue(denied.startsWith("[DENIED]"), denied);

        String ok = executor.execute(ToolCall.of("2", "echo", "{\"input\":\"hi\"}"));
        assertTrue(ok.startsWith("echo:"), ok);
    }

    private static Tool echo(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) { return "echo: " + args.path("input").asText(); }
        };
    }
}
