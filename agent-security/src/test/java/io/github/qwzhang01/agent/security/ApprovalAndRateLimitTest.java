package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 M9.2 (approval) + M9.3 (rate limiting) tests.
 */
class ApprovalAndRateLimitTest {

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
            @Override public String getDescription() { return "echo"; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) { return "ok: " + args.path("input").asText(); }
        };
    }

    private GovernedToolExecutor buildExecutor(ToolPolicy policy, ToolApprovalService approval,
                                                RateLimiter limiter) {
        registry.register(echoTool("get_time"));
        registry.register(echoTool("delete_file"));
        return GovernedToolExecutor.builder(defaultExecutor)
                .permissionChecker(new PermissionChecker(policy))
                .approvalService(approval)
                .rateLimiter(limiter)
                .auditLogger(audit)
                .build();
    }

    // ============ Approval (M9.2) ============

    @Test
    void requiresApproval_approved_executes() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL);
        GovernedToolExecutor executor = buildExecutor(policy,
                ConsoleApprovalService.autoApprove(), null);

        String result = executor.execute(call("delete_file"));
        assertTrue(result.startsWith("ok: test"));

        // Two audit events: APPROVED + EXECUTED
        assertEquals(2, audit.getAll().size());
        assertEquals(AuditEvent.AuditStatus.APPROVED, audit.getAll().get(0).status());
        assertEquals(AuditEvent.AuditStatus.EXECUTED, audit.getAll().get(1).status());
    }

    @Test
    void requiresApproval_rejected_notExecuted() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL);
        GovernedToolExecutor executor = buildExecutor(policy,
                ConsoleApprovalService.autoReject(), null);

        String result = executor.execute(call("delete_file"));
        assertTrue(result.startsWith("[DENIED]"));
        assertTrue(result.contains("Approval rejected"));

        // One audit event: DENIED
        assertEquals(1, audit.getAll().size());
        assertEquals(AuditEvent.AuditStatus.DENIED, audit.getAll().get(0).status());
    }

    @Test
    void autoTool_noApprovalNeeded() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        GovernedToolExecutor executor = buildExecutor(policy,
                ConsoleApprovalService.autoReject(), null); // autoReject, but shouldn't be called

        String result = executor.execute(call("get_time"));
        assertTrue(result.startsWith("ok: test"));

        // Only one event: EXECUTED (no APPROVED because AUTO doesn't need approval)
        assertEquals(1, audit.getAll().size());
        assertEquals(AuditEvent.AuditStatus.EXECUTED, audit.getAll().get(0).status());
    }

    @Test
    void approval_callback_customLogic() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.REQUIRES_APPROVAL);
        // Approve only if tool name contains "safe"
        ToolApprovalService approval = ConsoleApprovalService.callback(
                tc -> tc.name().contains("safe"));

        registry.register(echoTool("safe_op"));
        registry.register(echoTool("dangerous_op"));
        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .permissionChecker(new PermissionChecker(policy))
                .approvalService(approval)
                .auditLogger(audit)
                .build();

        assertTrue(executor.execute(call("safe_op")).startsWith("ok:"));
        assertTrue(executor.execute(call("dangerous_op")).startsWith("[DENIED]"));
    }

    // ============ Rate Limiting (M9.3) ============

    @Test
    void rateLimiter_allowsUpToLimit() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        SimpleRateLimiter limiter = new SimpleRateLimiter(3); // 3 calls per minute
        GovernedToolExecutor executor = buildExecutor(policy, null, limiter);

        // First 3 calls succeed
        assertTrue(executor.execute(call("get_time")).startsWith("ok:"));
        assertTrue(executor.execute(call("get_time")).startsWith("ok:"));
        assertTrue(executor.execute(call("get_time")).startsWith("ok:"));

        // 4th call is rate-limited
        String result = executor.execute(call("get_time"));
        assertTrue(result.startsWith("[RATE_LIMITED]"));
    }

    @Test
    void rateLimiter_deniedEventAudited() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        SimpleRateLimiter limiter = new SimpleRateLimiter(1);
        GovernedToolExecutor executor = buildExecutor(policy, null, limiter);

        executor.execute(call("get_time")); // ok
        executor.execute(call("get_time")); // rate limited

        assertEquals(2, audit.getAll().size());
        assertEquals(AuditEvent.AuditStatus.EXECUTED, audit.getAll().get(0).status());
        assertEquals(AuditEvent.AuditStatus.DENIED, audit.getAll().get(1).status());
        assertNotNull(audit.getAll().get(1).reason());
        assertTrue(audit.getAll().get(1).reason().contains("Rate limit"));
    }

    @Test
    void rateLimiter_perToolIsolation() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        SimpleRateLimiter limiter = new SimpleRateLimiter(1); // 1 per tool
        GovernedToolExecutor executor = buildExecutor(policy, null, limiter);

        // get_time: 1st ok, 2nd limited
        assertTrue(executor.execute(call("get_time")).startsWith("ok:"));
        assertTrue(executor.execute(call("get_time")).startsWith("[RATE_LIMITED]"));

        // delete_file: different tool, 1st ok (separate counter)
        assertTrue(executor.execute(call("delete_file")).startsWith("ok:"));
    }

    @Test
    void rateLimiter_null_noLimiting() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO);
        GovernedToolExecutor executor = buildExecutor(policy, null, null);

        // Call many times - no rate limiting
        for (int i = 0; i < 10; i++) {
            assertTrue(executor.execute(call("get_time")).startsWith("ok:"));
        }
    }

    // ============ Combined: permission + approval + rate limit ============

    @Test
    void combined_allGovernanceActive() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL);
        SimpleRateLimiter limiter = new SimpleRateLimiter(5);
        GovernedToolExecutor executor = buildExecutor(policy,
                ConsoleApprovalService.autoApprove(), limiter);

        // delete_file needs approval + rate limited
        assertTrue(executor.execute(call("delete_file")).startsWith("ok:"));
        assertTrue(executor.execute(call("delete_file")).startsWith("ok:"));

        // get_time is auto + rate limited by same limiter (but different tool counter)
        assertTrue(executor.execute(call("get_time")).startsWith("ok:"));
    }
}
