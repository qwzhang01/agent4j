package io.github.qwzhang01.agent.coding;

import io.github.qwzhang01.agent.coding.exec.CommandRunner;
import io.github.qwzhang01.agent.coding.exec.CommandWhitelist;
import io.github.qwzhang01.agent.coding.patch.Patch;
import io.github.qwzhang01.agent.coding.session.CodingSession;
import io.github.qwzhang01.agent.coding.session.FixLoopPolicy;
import io.github.qwzhang01.agent.coding.workspace.Workspace;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.security.InMemoryAuditLogger;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.5: the assembly - five tools from the session, the D8 permission
 * tiers, the coding system prompt, and a governed ReAct loop producing a runnable
 * Agent with zero changes to any existing module.
 */
class CodingAgentFactoryTest {

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(root.resolve("App.java"), "class App {}\n");
    }

    private CodingSession session() {
        return CodingSession.builder()
                .workspace(Workspace.open(root))
                .whitelist(CommandWhitelist.builder().rule("echo").build())
                .runner(new CommandRunner(root))
                .testCommand(List.of("echo", "ok"))
                .fixLoopPolicy(FixLoopPolicy.DEFAULT)
                .build();
    }

    @Test
    @DisplayName("D8 tiers: reads/staging AUTO, run_command/run_tests REQUIRES_APPROVAL")
    void permissionTiersFollowSideEffects() {
        ToolPolicy policy = CodingAgentFactory.defaultPolicy();

        assertEquals(ToolPermission.AUTO, policy.permissionFor("read_file"));
        assertEquals(ToolPermission.AUTO, policy.permissionFor("list_files"));
        assertEquals(ToolPermission.AUTO, policy.permissionFor("write_file"));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, policy.permissionFor("run_command"));
        assertEquals(ToolPermission.REQUIRES_APPROVAL, policy.permissionFor("run_tests"));
    }

    @Test
    @DisplayName("the system prompt is the coding behavior contract")
    void systemPromptIsTheContract() {
        String prompt = CodingAgentFactory.defaultSystemPrompt();

        assertTrue(prompt.contains("READ BEFORE YOU CHANGE"), prompt);
        assertTrue(prompt.contains("THE TEST IS THE JUDGE"), prompt);
        assertTrue(prompt.contains("FIX BUDGET"), prompt);
        assertTrue(prompt.contains("SUMMARY"), prompt);
    }

    @Test
    @DisplayName("create assembles a runnable governed agent: a full scripted coding turn")
    void assemblesRunnableAgent() {
        MockModelClient model = MockModelClient.scripted()
                .respondText("I read the code and staged nothing. Done.");
        InMemoryAuditLogger audit = new InMemoryAuditLogger();

        Agent agent = CodingAgentFactory.create(session(), model,
                io.github.qwzhang01.agent.security.ConsoleApprovalService.autoApprove(), audit);

        assertNotNull(agent);
        assertEquals("coding-agent", agent.getConfig().getName());
        assertEquals(CodingAgentFactory.DEFAULT_MAX_STEPS, agent.getConfig().getMaxSteps());

        String answer = agent.run("Take a look at App.java");
        assertEquals("I read the code and staged nothing. Done.", answer);
    }

    @Test
    @DisplayName("the demo convenience wires auto-approval and in-memory audit")
    void demoConvenience() {
        MockModelClient model = MockModelClient.scripted().respondText("ok");

        Agent agent = CodingAgentFactory.createDemoAgent(session(), model);

        assertEquals("ok", agent.run("hi"));
    }

    @Test
    @DisplayName("factory guards: null parts fail fast")
    void guards() {
        assertThrows(NullPointerException.class,
                () -> CodingAgentFactory.create(null, MockModelClient.scripted().respondText("x"),
                        io.github.qwzhang01.agent.security.ConsoleApprovalService.autoApprove(),
                        new InMemoryAuditLogger()));
        assertThrows(NullPointerException.class,
                () -> CodingAgentFactory.create(session(), null,
                        io.github.qwzhang01.agent.security.ConsoleApprovalService.autoApprove(),
                        new InMemoryAuditLogger()));
    }

    @Test
    @DisplayName("a full scripted coding loop through the governed agent: stage -> test fails -> fix -> pass")
    void fullLoopThroughGovernance() throws Exception {
        // the referee: a command that fails while App.java lacks 'x', passes once staged
        Path checker = Files.writeString(root.resolve("check.sh"),
                "#!/bin/sh\nif grep -q 'int x' App.java; then echo 'Tests run: 1, Failures: 0'; "
                        + "else echo 'Tests run: 1, Failures: 1 - int x missing'; exit 1; fi\n");
        Files.setPosixFilePermissions(checker,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        CodingSession codingSession = CodingSession.builder()
                .workspace(Workspace.open(root))
                .whitelist(CommandWhitelist.builder().rule("./check.sh").build())
                .runner(new CommandRunner(root, Duration.ofSeconds(10), 64 * 1024))
                .testCommand(List.of("./check.sh"))
                .fixLoopPolicy(new FixLoopPolicy(3))
                .build();
        InMemoryAuditLogger audit = new InMemoryAuditLogger();

        MockModelClient model = MockModelClient.scripted()
                // step 1: look around
                .respondToolCalls(io.github.qwzhang01.agent.core.model.ToolCall.of(
                        "c1", "list_files", "{\"max_depth\":0}"))
                // step 2: stage the change
                .respondToolCalls(io.github.qwzhang01.agent.core.model.ToolCall.of(
                        "c2", "write_file",
                        "{\"path\":\"App.java\",\"content\":\"class App { int x; }\"}"))
                // step 3: run the tests (materialize + referee)
                .respondToolCalls(io.github.qwzhang01.agent.core.model.ToolCall.of(
                        "c3", "run_tests", "{}"))
                // step 4: deliver the summary
                .respondText("Staged and validated: App.java now declares int x. Tests green.");

        Agent agent = CodingAgentFactory.create(codingSession, model,
                io.github.qwzhang01.agent.security.ConsoleApprovalService.autoApprove(), audit);

        String answer = agent.run("Add an int x field to App.java and verify with tests.");

        assertTrue(answer.contains("Tests green"), answer);
        assertEquals("class App { int x; }", Files.readString(root.resolve("App.java")),
                "the referee materialized the staged change before judging");
        assertEquals(Patch.PatchStatus.VALIDATED, codingSession.activePatch().orElseThrow().status());
        // governance: write_file AUTO-executed, run_tests APPROVED-executed, all audited
        assertTrue(audit.getAll().size() >= 3, "every tool call went through the chain: "
                + audit.getAll().size());
        assertTrue(audit.getAll().stream().anyMatch(e -> "run_tests".equals(e.toolName())),
                "run_tests is in the audit trail");
    }
}
