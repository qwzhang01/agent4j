package io.github.qwzhang01.agent.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.GenerationTools;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationGovernanceTest {

    @Test
    void generationDefaultsRequireApproval() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO).applyGenerationDefaults();
        assertEquals(ToolPermission.REQUIRES_APPROVAL, policy.permissionFor(GenerationTools.DESCRIBE_IMAGE));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, policy.permissionFor(GenerationTools.GENERATE_IMAGE));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, policy.permissionFor(GenerationTools.GENERATE_VIDEO));
        assertEquals(ToolPermission.AUTO, policy.permissionFor("echo"));
    }

    @Test
    void generateVideoDeniedIsAudited() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(namedTool(GenerationTools.GENERATE_VIDEO));
        InMemoryAuditLogger audit = new InMemoryAuditLogger();
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission(GenerationTools.GENERATE_VIDEO, ToolPermission.DENY);

        GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(policy))
                .auditLogger(audit)
                .build();

        String result = executor.execute(ToolCall.of("c1", GenerationTools.GENERATE_VIDEO, "{\"prompt\":\"x\"}"));
        assertTrue(result.startsWith("[DENIED]"));
        assertEquals(1, audit.getAll().size());
        assertEquals(AuditEvent.AuditStatus.DENIED, audit.getAll().get(0).status());
    }

    @Test
    void generateImageApprovedThenExecuted() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(namedTool(GenerationTools.GENERATE_IMAGE));
        InMemoryAuditLogger audit = new InMemoryAuditLogger();
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO).applyGenerationDefaults();

        GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(policy))
                .approvalService(ConsoleApprovalService.autoApprove())
                .auditLogger(audit)
                .rateLimiter(new SimpleRateLimiter(5))
                .build();

        String result = executor.execute(ToolCall.of("c1", GenerationTools.GENERATE_IMAGE, "{\"prompt\":\"cat\"}"));
        assertEquals("ok:" + GenerationTools.GENERATE_IMAGE, result);
        assertTrue(audit.getAll().stream().anyMatch(e -> e.status() == AuditEvent.AuditStatus.APPROVED));
        assertTrue(audit.getAll().stream().anyMatch(e -> e.status() == AuditEvent.AuditStatus.EXECUTED));
    }

    private static Tool namedTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) { return "ok:" + name; }
        };
    }
}
