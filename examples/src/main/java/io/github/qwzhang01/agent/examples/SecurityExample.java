package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.security.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 9 acceptance example 1: tool governance (permission + approval + audit).
 * <p>
 * Demonstrates:
 * - Three permission tiers: AUTO (get_time) / REQUIRES_APPROVAL (delete_file) / DENY (format_disk)
 * - Approval service (auto-approve for demo)
 * - Audit log recording all events (executed + denied + approved)
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.SecurityExample
 */
public class SecurityExample {

    public static void main(String[] args) {
        System.out.println("=== Stage 9: Tool Governance (Permission + Approval + Audit) ===\n");

        // Setup tools
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(safeTool("get_time", "2026-08-19 12:00"));
        registry.register(dangerousTool("delete_file"));
        registry.register(dangerousTool("format_disk"));

        DefaultToolExecutor defaultExecutor = new DefaultToolExecutor(registry);
        InMemoryAuditLogger audit = new InMemoryAuditLogger();

        // Policy: get_time=AUTO, delete_file=REQUIRES_APPROVAL, format_disk=DENY
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL)
                .setPermission("format_disk", ToolPermission.DENY);

        GovernedToolExecutor executor = GovernedToolExecutor.builder(defaultExecutor)
                .permissionChecker(new PermissionChecker(policy))
                .approvalService(ConsoleApprovalService.autoApprove())
                .auditLogger(audit)
                .build();

        // ---- T1: AUTO tool - executes directly ----
        System.out.println("--- T1: AUTO tool (get_time) ---");
        String r1 = executor.execute(call("get_time", "/path"));
        System.out.println("Result: " + r1);
        System.out.println();

        // ---- T2: REQUIRES_APPROVAL tool - needs approval ----
        System.out.println("--- T2: REQUIRES_APPROVAL tool (delete_file) ---");
        String r2 = executor.execute(call("delete_file", "/tmp/important.txt"));
        System.out.println("Result: " + r2);
        System.out.println();

        // ---- T3: DENY tool - blocked outright ----
        System.out.println("--- T3: DENY tool (format_disk) ---");
        String r3 = executor.execute(call("format_disk", "/dev/sda1"));
        System.out.println("Result: " + r3);
        System.out.println();

        // ---- Audit trail ----
        System.out.println("--- Audit Trail ---");
        for (AuditEvent event : audit.getAll()) {
            System.out.printf("  [%s] %s | args=%s | result=%s | %dms | reason=%s%n",
                    event.status(),
                    event.toolName(),
                    truncate(event.args(), 40),
                    truncate(event.result(), 40),
                    event.durationMs(),
                    event.reason());
        }

        System.out.println("\n=== Acceptance: 3 tiers (AUTO/REQUIRES_APPROVAL/DENY) + audit trail ===");
    }

    private static ToolCall call(String name, String path) {
        return ToolCall.of("id-1", name, "{\"path\":\"" + path + "\"}");
    }

    private static Tool safeTool(String name, String result) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "safe tool"; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) { return result; }
        };
    }

    private static Tool dangerousTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "dangerous tool"; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) {
                return "executed " + name + " on " + args.path("path").asText();
            }
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
