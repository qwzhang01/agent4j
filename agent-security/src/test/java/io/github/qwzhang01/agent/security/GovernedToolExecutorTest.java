package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 M9.1 tests: permission tiers, policy, governed executor decorator.
 */
class GovernedToolExecutorTest {

    private InMemoryToolRegistry registry;
    private DefaultToolExecutor defaultExecutor;
    private InMemoryAuditLogger audit;

    @BeforeEach
    void setUp() {
        registry = new InMemoryToolRegistry();
        defaultExecutor = new DefaultToolExecutor(registry);
        audit = new InMemoryAuditLogger();
    }

    private ToolCall call(String name) {
        return ToolCall.of("id-1", name, "{\"input\":\"test\"}");
    }

    private Tool echoTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "echo tool"; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) { return "echo: " + args.path("input").asText(); }
        };
    }

    private Tool failingTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "always fails"; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) throws ToolException {
                throw new ToolException("intentional failure");
            }
        };
    }

    // ============ Permission Tiers ============

    @Test
    void autoTool_executesDirectly() {
        registry.register(echoTool("get_time"));
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        PermissionChecker checker = new PermissionChecker(policy);

        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .permissionChecker(checker)
                .auditLogger(audit)
                .build();

        String result = executor.execute(call("get_time"));
        assertTrue(result.startsWith("echo: test"));
        assertEquals(1, audit.getAll().size());
        assertEquals(AuditEvent.AuditStatus.EXECUTED, audit.getAll().get(0).status());
    }

    @Test
    void denyTool_rejectedWithoutExecution() {
        registry.register(echoTool("dangerous"));
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("dangerous", ToolPermission.DENY);
        PermissionChecker checker = new PermissionChecker(policy);

        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .permissionChecker(checker)
                .auditLogger(audit)
                .build();

        String result = executor.execute(call("dangerous"));
        assertTrue(result.startsWith("[DENIED]"));
        assertEquals(1, audit.getAll().size());
        assertEquals(AuditEvent.AuditStatus.DENIED, audit.getAll().get(0).status());
    }

    @Test
    void requiresApproval_withoutApprovalService_denied() {
        registry.register(echoTool("delete_file"));
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL);
        PermissionChecker checker = new PermissionChecker(policy);

        // No approval service configured
        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .permissionChecker(checker)
                .auditLogger(audit)
                .build();

        String result = executor.execute(call("delete_file"));
        assertTrue(result.startsWith("[DENIED]"));
        assertTrue(result.contains("no approval service"));
        assertEquals(AuditEvent.AuditStatus.DENIED, audit.getAll().get(0).status());
    }

    // ============ Audit ============

    @Test
    void audit_recordsExecutionWithDuration() {
        registry.register(echoTool("get_time"));
        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .auditLogger(audit)
                .build();

        executor.execute(call("get_time"));

        AuditEvent event = audit.getAll().get(0);
        assertEquals("get_time", event.toolName());
        assertEquals(AuditEvent.AuditStatus.EXECUTED, event.status());
        assertTrue(event.durationMs() >= 0);
        assertNotNull(event.result());
    }

    @Test
    void audit_recordsDeniedEvents() {
        registry.register(echoTool("forbidden"));
        ToolPolicy policy = new ToolPolicy(ToolPermission.DENY);
        PermissionChecker checker = new PermissionChecker(policy);

        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .permissionChecker(checker)
                .auditLogger(audit)
                .build();

        executor.execute(call("forbidden"));

        AuditEvent event = audit.getAll().get(0);
        assertEquals(AuditEvent.AuditStatus.DENIED, event.status());
        assertNotNull(event.reason());
        assertNull(event.result());
    }

    @Test
    void audit_recordsFailedExecution() {
        registry.register(failingTool("boom"));
        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .auditLogger(audit)
                .build();

        // The failing tool throws; DefaultToolExecutor catches and returns [ERROR]
        // So it won't throw - but audit records EXECUTED (the catch is inside delegate)
        String result = executor.execute(call("boom"));
        assertTrue(result.startsWith("[ERROR]"));
        assertEquals(AuditEvent.AuditStatus.EXECUTED, audit.getAll().get(0).status());
    }

    @Test
    void audit_runIdTracked() {
        registry.register(echoTool("get_time"));
        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .auditLogger(audit)
                .runId("run-42")
                .build();

        executor.execute(call("get_time"));

        AuditEvent event = audit.getByRun("run-42").get(0);
        assertEquals("run-42", event.runId());
    }

    @Test
    void audit_byTool() {
        registry.register(echoTool("get_time"));
        registry.register(echoTool("echo"));
        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .auditLogger(audit)
                .build();

        executor.execute(call("get_time"));
        executor.execute(call("echo"));

        assertEquals(2, audit.getAll().size());
        assertEquals(1, audit.getByTool("get_time").size());
        assertEquals(1, audit.getByTool("echo").size());
    }

    // ============ Backward Compatibility ============

    @Test
    void noGovernance_behavesLikeDefaultExecutor() {
        registry.register(echoTool("get_time"));
        // GovernedToolExecutor with all governance components null
        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor).build();

        String result = executor.execute(call("get_time"));
        assertTrue(result.startsWith("echo: test"));
    }

    @Test
    void defaultPolicy_appliesToUnregisteredTools() {
        registry.register(echoTool("unknown_tool"));
        ToolPolicy policy = new ToolPolicy(ToolPermission.REQUIRES_APPROVAL);
        PermissionChecker checker = new PermissionChecker(policy);

        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .permissionChecker(checker)
                .auditLogger(audit)
                .build();

        String result = executor.execute(call("unknown_tool"));
        assertTrue(result.startsWith("[DENIED]")); // no approval service
    }

    // ============ ToolPolicy ============

    @Test
    void policy_setAndRemovePermission() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        assertEquals(ToolPermission.AUTO, policy.permissionFor("anything"));

        policy.setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL);
        assertEquals(ToolPermission.REQUIRES_APPROVAL, policy.permissionFor("delete_file"));
        assertEquals(ToolPermission.AUTO, policy.permissionFor("other"));

        policy.removePermission("delete_file");
        assertEquals(ToolPermission.AUTO, policy.permissionFor("delete_file"));
    }

    @Test
    void policy_bulkSet() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        policy.setAll(java.util.Map.of(
                "a", ToolPermission.DENY,
                "b", ToolPermission.REQUIRES_APPROVAL
        ));
        assertEquals(ToolPermission.DENY, policy.permissionFor("a"));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, policy.permissionFor("b"));
        assertEquals(2, policy.getAllPermissions().size());
    }

    // ============ PermissionChecker ============

    @Test
    void checker_helpers() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("deny", ToolPermission.DENY)
                .setPermission("approve", ToolPermission.REQUIRES_APPROVAL);
        PermissionChecker checker = new PermissionChecker(policy);

        assertTrue(checker.isAuto("anything"));
        assertTrue(checker.isDenied("deny"));
        assertTrue(checker.requiresApproval("approve"));
        assertFalse(checker.isDenied("approve"));
        assertFalse(checker.requiresApproval("anything"));
    }
}
