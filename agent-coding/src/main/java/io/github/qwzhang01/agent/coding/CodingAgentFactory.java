package io.github.qwzhang01.agent.coding;

import io.github.qwzhang01.agent.coding.session.CodingSession;
import io.github.qwzhang01.agent.coding.workspace.ListFilesTool;
import io.github.qwzhang01.agent.coding.workspace.ReadFileTool;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.security.AuditLogger;
import io.github.qwzhang01.agent.security.ConsoleApprovalService;
import io.github.qwzhang01.agent.security.GovernedToolExecutor;
import io.github.qwzhang01.agent.security.PermissionChecker;
import io.github.qwzhang01.agent.security.ToolApprovalService;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;

import java.util.Objects;

/**
 * Assembles the Coding Agent (Stage 17 M17.5) - the third Profile on the shared
 * Runtime, and the fifth assembly of the same parts (core + security + domain).
 * <p>
 * What lands where (blueprint D8): the permission tiers follow <b>real side effects</b> -
 * {@code read_file}/{@code list_files} are AUTO (zero side effects),
 * {@code write_file} is AUTO (staging only, disk untouched - the real write is the
 * human-gated apply), {@code run_command} and {@code run_tests} are REQUIRES_APPROVAL
 * (process execution has immediate side effects). The apply gate itself is
 * {@link CodingSession#approveAndApply()} - a human reading a diff, not a tool call.
 * <p>
 * The system prompt is the coding behavior contract (blueprint: read before change /
 * small staged patches / the test is the judge / [LIMIT] means report honestly).
 * <p>
 * Zero changes to existing modules: this factory only composes what Stages 1-16 and
 * M17.1-M17.4 already provide.
 */
public final class CodingAgentFactory {

    public static final String AGENT_NAME = "coding-agent";

    /** Default max ReAct steps: a coding task is longer than a chat, but not unbounded. */
    public static final int DEFAULT_MAX_STEPS = 24;

    private CodingAgentFactory() {
    }

    /**
     * The D8 permission tiers: AUTO for side-effect-free tools, REQUIRES_APPROVAL for
     * the two that execute processes. DENY is not used here - unknown tools simply do
     * not exist in the registry (fail-closed by construction, not by policy entry).
     */
    public static ToolPolicy defaultPolicy() {
        return new ToolPolicy(ToolPermission.AUTO)
                .setPermission("run_command", ToolPermission.REQUIRES_APPROVAL)
                .setPermission("run_tests", ToolPermission.REQUIRES_APPROVAL);
    }

    /** The coding behavior contract (blueprint: read first / small patches / test as judge). */
    public static String defaultSystemPrompt() {
        return """
                You are a coding agent working in a source workspace.

                Behavior contract:
                1. READ BEFORE YOU CHANGE. Use list_files and read_file to understand the code
                   you are about to modify. Never guess file contents.
                2. STAGE SMALL PATCHES. write_file stages changes - nothing touches the disk
                   until a human approves the patch. Keep each patch focused on the task.
                3. THE TEST IS THE JUDGE. After staging, call run_tests and read the output
                   excerpt. If it failed, understand WHY (read the failure lines), fix the
                   staged file, and run_tests again.
                4. RESPECT THE FIX BUDGET. A [LIMIT] result means the fix budget is exhausted:
                   stop fixing, report what failed and what you tried, honestly.
                5. COMMANDS ARE GUESTS. run_command goes through approval and a whitelist.
                   A [REJECTED] result lists what is allowed - pick a legal command or proceed
                   without it. Never try to work around the whitelist.
                6. DELIVER A SUMMARY. Finish with: which files changed (create/modify/delete),
                   what the change does, and the final test status.
                """;
    }

    /**
     * Assemble the agent: five tools from the session, the governance chain
     * (D8 tiers + approval + audit), the coding system prompt, a governed ReAct loop.
     *
     * @param session         the governance shell (tools, patch store, fix budget)
     * @param model           the model client (Mock in tests/examples, real LLM in prod)
     * @param approvalService REQUIRES_APPROVAL handler ({@code ConsoleApprovalService.autoApprove()}
     *                        for demos, {@code console()} for a human in the terminal)
     * @param auditLogger     the audit trail (InMemoryAuditLogger in demos)
     */
    public static Agent create(CodingSession session, ModelClient model,
                               ToolApprovalService approvalService, AuditLogger auditLogger) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(approvalService, "approvalService must not be null");
        Objects.requireNonNull(auditLogger, "auditLogger must not be null");

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(session.readFileTool());
        registry.register(session.listFilesTool());
        registry.register(session.writeFileTool());
        registry.register(session.runCommandTool());
        registry.register(session.runTestsTool());

        GovernedToolExecutor executor = GovernedToolExecutor.builder(
                        new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(defaultPolicy()))
                .approvalService(approvalService)
                .auditLogger(auditLogger)
                .build();

        AgentConfig config = new AgentConfig(AGENT_NAME, defaultSystemPrompt(),
                model, registry, DEFAULT_MAX_STEPS);
        return new SimpleAgent(config, new ReActAgentLoop(executor));
    }

    /** Demo convenience: auto-approving governance, in-memory audit. */
    public static Agent createDemoAgent(CodingSession session, ModelClient model) {
        return create(session, model, ConsoleApprovalService.autoApprove(),
                new io.github.qwzhang01.agent.security.InMemoryAuditLogger());
    }
}
