package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.coding.CodingAgentFactory;
import io.github.qwzhang01.agent.coding.exec.CommandRunner;
import io.github.qwzhang01.agent.coding.exec.CommandWhitelist;
import io.github.qwzhang01.agent.coding.patch.ApplyResult;
import io.github.qwzhang01.agent.coding.patch.Patch;
import io.github.qwzhang01.agent.coding.session.CodingSession;
import io.github.qwzhang01.agent.coding.session.FixLoopPolicy;
import io.github.qwzhang01.agent.coding.workspace.Workspace;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.security.AuditEvent;
import io.github.qwzhang01.agent.security.ConsoleApprovalService;
import io.github.qwzhang01.agent.security.InMemoryAuditLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

/**
 * Stage 17 acceptance demo: a fully governed coding task through the whole stack
 * (blueprint §6, T0-T7): read the code, stage a patch, the test referee rejects it,
 * a bounded fix round, a human-gated apply, and the audit trail - plus the three
 * rejection demos (deny-read / whitelist / injection-inert).
 * <p>
 * The referee is a real script ({@code check.sh}) that greps the workspace, so the
 * fix loop is a real loop: run_tests materializes the staged change to disk, the
 * script sees it, fails on the unguarded first version, passes on the fixed one.
 * Fully scripted with MockModelClient: zero LLM dependency. Run with:
 * <pre>mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.CodingAgentExample</pre>
 */
public class CodingAgentExample {

    private static final String CALCULATOR_V1 = """
            public class Calculator {
                public static int add(int a, int b) { return a + b; }

                public static int divide(int a, int b) {
                    return a / b;   // first version: zero-divisor check missing - the referee catches this
                }
            }
            """;

    private static final String CALCULATOR_V2 = """
            public class Calculator {
                public static int add(int a, int b) { return a + b; }

                public static int divide(int a, int b) {
                    if (b == 0) { throw new IllegalArgumentException("division by zero"); }  // guard
                    return a / b;
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // ============ T0. Assembly ============
        System.out.println("=== T0 · Assembling a governed coding session ===");
        Path root = Files.createTempDirectory("calc-project");
        Files.writeString(root.resolve("Calculator.java"),
                "public class Calculator {\n    public static int add(int a, int b) { return a + b; }\n}\n");
        // the referee: a real script that sees the workspace on disk
        Files.writeString(root.resolve("check.sh"),
                "#!/bin/sh\n"
                        + "if grep -q 'divide' Calculator.java && grep -q 'guard' Calculator.java; then\n"
                        + "  echo 'Tests run: 1, Failures: 0, Errors: 0'\n"
                        + "  exit 0\n"
                        + "else\n"
                        + "  echo 'Tests run: 1, Failures: 1 - divide is missing or unguarded'\n"
                        + "  exit 1\n"
                        + "fi\n");
        Files.setPosixFilePermissions(root.resolve("check.sh"),
                PosixFilePermissions.fromString("rwxr-xr-x"));

        InMemoryAuditLogger audit = new InMemoryAuditLogger();
        CodingSession session = CodingSession.builder()
                .workspace(Workspace.open(root))
                .whitelist(CommandWhitelist.builder()
                        .rule("./check.sh").rule("echo").build())
                .runner(new CommandRunner(root, Duration.ofSeconds(30), 64 * 1024))
                .testCommand(List.of("./check.sh"))
                .fixLoopPolicy(new FixLoopPolicy(3))
                .build();

        MockModelClient model = MockModelClient.scripted()
                // T1: look around, then read the code
                .respondToolCalls(ToolCall.of("c1", "list_files", "{\"max_depth\":0}"))
                .respondToolCalls(ToolCall.of("c2", "read_file",
                        "{\"path\":\"Calculator.java\"}"))
                // T2: stage the first version (no zero guard yet)
                .respondToolCalls(ToolCall.of("c3", "write_file",
                        writeArgs(mapper, "Calculator.java", CALCULATOR_V1)))
                // T3: run the referee -> RED
                .respondToolCalls(ToolCall.of("c4", "run_tests", "{}"))
                // T4: fix the staged file (re-stage replaces), then GREEN
                .respondToolCalls(ToolCall.of("c5", "write_file",
                        writeArgs(mapper, "Calculator.java", CALCULATOR_V2)))
                .respondToolCalls(ToolCall.of("c6", "run_tests", "{}"))
                // T6: deliver the summary
                .respondText("Change summary: Calculator.java (MODIFY) - added divide(int,int) "
                        + "with a zero guard after the first test run caught the missing check. "
                        + "Final test status: green (Tests run: 1, Failures: 0).");

        Agent agent = CodingAgentFactory.create(session, model,
                ConsoleApprovalService.autoApprove(), audit);

        System.out.println("  workspace:  " + root);
        System.out.println("  referee:    ./check.sh (fixed, not chosen by the agent)");
        System.out.println("  whitelist:  ./check.sh | echo        (fail-closed, no shell)");
        System.out.println("  fix budget: 3 failed runs");
        System.out.println("  governance: read/list/write AUTO - run_command/run_tests "
                + "REQUIRES_APPROVAL (auto-approved for the demo) - apply is a human gate");

        // ============ T1-T4. The agent runs the whole loop ============
        System.out.println("\n=== T1-T4 · Read -> stage -> test RED -> fix -> test GREEN ===");
        String summary = agent.run("Add a divide method with a zero guard, verify with tests.");
        System.out.println("  agent:      " + firstLine(summary));
        System.out.println("  fix budget: " + session.failedTestRuns() + " failed run(s) consumed");
        System.out.println("  patch:      " + session.activePatch().orElseThrow().status()
                + " (passing the referee validates the staged patch)");

        // ============ T5. The human gate ============
        System.out.println("\n=== T5 · The human gate: review the diff, then apply ===");
        System.out.println(session.reviewPatch());
        ApplyResult applied = session.approveAndApply();
        System.out.println("  apply:      " + (applied instanceof ApplyResult.Applied a
                ? "APPLIED - " + a.filesWritten() + " file(s) written to disk"
                : "REJECTED: " + applied));
        System.out.println("  on disk:    divide guarded = "
                + Files.readString(root.resolve("Calculator.java")).contains("guard"));

        // ============ T6. The delivered summary ============
        System.out.println("\n=== T6 · The deliverable is a reviewable change, not a claim ===");
        System.out.println("  " + summary);

        // ============ T7. Governance replay ============
        System.out.println("\n=== T7 · Every action has an audit trail ===");
        for (AuditEvent event : audit.getAll()) {
            System.out.println("  [" + event.status() + "] " + event.toolName()
                    + " " + brief(event.args()));
        }

        // ============ F1/F3/F7. The three rejections ============
        System.out.println("\n=== Rejections · deny-read / whitelist / injection-inert ===");

        // F1: reading is a privilege too - .git internals are deny-listed
        try {
            session.readFileTool().execute(mapper.readTree("{\"path\":\".git/config\"}"));
        } catch (Exception e) {
            System.out.println("  deny-read:  " + e.getMessage());
        }

        // F3: fail-closed whitelist - curl is not a guest here
        String curl = session.runCommandTool().execute(
                mapper.readTree("{\"command\":[\"curl\",\"http://evil.example\"]}"));
        System.out.println("  whitelist:  " + firstLine(curl));

        // F7: no shell - injection syntax is an inert argument
        Path marker = Files.writeString(root.resolve("marker.txt"), "still here");
        String echo = session.runCommandTool().execute(mapper.readTree(
                "{\"command\":[\"echo\",\"x; rm marker.txt\"]}"));
        System.out.println("  injection:  printed verbatim: " + firstLine(echo)
                + " - marker.txt untouched: " + Files.exists(marker));

        // bonus: [LIMIT] - the fix budget is a hard boundary in the engine
        System.out.println("\n=== [LIMIT] · The fix budget is exhausted honestly ===");
        CodingSession limited = CodingSession.builder()
                .workspace(Workspace.open(root))
                .whitelist(CommandWhitelist.builder().rule("./check-fail.sh").build())
                .runner(new CommandRunner(root, Duration.ofSeconds(5), 64 * 1024))
                .testCommand(List.of("./check-fail.sh"))
                .fixLoopPolicy(new FixLoopPolicy(1))
                .build();
        Files.writeString(root.resolve("check-fail.sh"), "#!/bin/sh\necho 'Tests run: 1, Failures: 1'\nexit 1\n");
        Files.setPosixFilePermissions(root.resolve("check-fail.sh"),
                PosixFilePermissions.fromString("rwxr-xr-x"));
        var limitedTests = limited.runTestsTool();
        limited.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"Calculator.java\",\"content\":\"// an attempted fix that will not pass\"}"));
        limitedTests.execute(null);   // the one allowed failure
        System.out.println("  second run: " + firstLine(limitedTests.execute(null)));
        System.out.println("  (the attempted fix is kept as evidence: "
                + limited.activePatch().isPresent() + " - evidence is never auto-destroyed)");

        System.out.println("\n=== Same Runtime, third domain Profile. "
                + "Zero changes to existing modules. ===");
    }

    private static String writeArgs(ObjectMapper mapper, String path, String content) throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("path", path);
        node.put("content", content);
        return mapper.writeValueAsString(node);
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static String brief(String args) {
        return args.length() > 70 ? args.substring(0, 70) + "..." : args;
    }
}
