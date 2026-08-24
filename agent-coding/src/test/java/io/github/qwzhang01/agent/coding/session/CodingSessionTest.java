package io.github.qwzhang01.agent.coding.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.coding.exec.CommandRunner;
import io.github.qwzhang01.agent.coding.exec.CommandWhitelist;
import io.github.qwzhang01.agent.coding.patch.ApplyResult;
import io.github.qwzhang01.agent.coding.patch.Patch;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.coding.workspace.Workspace;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.4: the governance shell - fix-loop counting (FAILED only), the [LIMIT]
 * veto, passing-is-leaving-the-loop (VALIDATED), and the full patch state machine
 * through the human gates (DRAFT -> VALIDATED -> APPLIED / REJECTED / DISCARDED).
 * <p>
 * Test commands are real POSIX processes: "echo ok" plays a passing suite,
 * "ls /definitely-not-there" plays a failing one.
 */
class CodingSessionTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private Path root;
    private CodingSession session;
    private Tool runTests;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(root.resolve("App.java"), "class App {}\n");
    }

    private CodingSession session(String testCommand, int maxFix) {
        return session(List.of("echo", testCommand), maxFix);
    }

    private CodingSession session(List<String> testCommand, int maxFix) {
        return CodingSession.builder()
                .workspace(Workspace.open(root))
                .whitelist(CommandWhitelist.builder()
                        .rule("echo").rule("ls").rule("sleep").build())
                .runner(new CommandRunner(root, Duration.ofSeconds(10), 64 * 1024))
                .testCommand(testCommand)
                .fixLoopPolicy(new FixLoopPolicy(maxFix))
                .build();
    }

    // ============ Fix-loop counting ============

    @Test
    @DisplayName("failed runs count up; passing runs do not")
    void failedRunsCountOnlyFailures() throws Exception {
        session = session("ok", 3);
        runTests = session.runTestsTool();

        runTests.execute(null);                          // pass
        assertEquals(0, session.failedTestRuns());

        session = session(List.of("ls", "/definitely-not-there-xyz"), 3);
        runTests = session.runTestsTool();
        runTests.execute(null);                          // fail
        runTests.execute(null);                          // fail
        assertEquals(2, session.failedTestRuns());
    }

    @Test
    @DisplayName("[LIMIT]: once the budget is exhausted, run_tests refuses to execute")
    void limitVeto() throws Exception {
        session = session(List.of("ls", "/definitely-not-there-xyz"), 1);
        runTests = session.runTestsTool();

        String first = runTests.execute(null);           // the one allowed failure
        assertTrue(mapper.readTree(first).get("passed").asBoolean() == false);

        String second = runTests.execute(null);
        assertTrue(second.startsWith("[LIMIT]"), second);
        assertTrue(second.contains("fix budget exhausted"), second);
        assertTrue(second.contains("report"), "tells the model to report honestly: " + second);
        assertEquals(1, session.failedTestRuns(), "the vetoed call must not consume budget");
    }

    @Test
    @DisplayName("[LIMIT] keeps the staged patch as evidence (nothing auto-discarded)")
    void limitKeepsPatch() throws Exception {
        session = session(List.of("ls", "/definitely-not-there-xyz"), 1);
        runTests = session.runTestsTool();
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"attempted fix\"}"));

        runTests.execute(null);                          // fails, budget 1 -> exhausted
        String vetoed = runTests.execute(null);
        assertTrue(vetoed.startsWith("[LIMIT]"), vetoed);

        Patch patch = session.activePatch().orElseThrow();
        assertEquals(Patch.PatchStatus.DRAFT, patch.status(),
                "the attempted fix is kept as review evidence");
    }

    // ============ Passing is leaving the loop ============

    @Test
    @DisplayName("a passing run transitions the staged patch to VALIDATED")
    void passingValidatesPatch() throws Exception {
        session = session("Tests run: 1, Failures: 0", 3);
        runTests = session.runTestsTool();
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"class App { int x; }\"}"));

        String result = runTests.execute(null);

        assertTrue(mapper.readTree(result).get("passed").asBoolean());
        assertEquals(Patch.PatchStatus.VALIDATED, session.activePatch().orElseThrow().status());
    }

    @Test
    @DisplayName("run_tests materializes the staged changes so the referee can see them (T3's premise)")
    void runTestsMaterializes() throws Exception {
        session = session("Tests run: 1, Failures: 0", 3);
        runTests = session.runTestsTool();
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"class App { int x; }\"}"));

        runTests.execute(null);

        // blueprint T3's hidden premise, honored: the test command ran against the staged state
        assertEquals("class App { int x; }", Files.readString(root.resolve("App.java")),
                "the referee saw the staged changes on disk");
    }

    @Test
    @DisplayName("rejectPatch after a materialized test run restores the original disk")
    void rejectRestoresMaterializedDisk() throws Exception {
        session = session("Tests run: 1, Failures: 0", 3);
        runTests = session.runTestsTool();
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"would-be change\"}"));
        runTests.execute(null);                        // materializes the patch
        assertEquals("would-be change", Files.readString(root.resolve("App.java")));

        Patch rejected = session.rejectPatch();

        assertEquals(Patch.PatchStatus.REJECTED, rejected.status());
        assertEquals("class App {}\n", Files.readString(root.resolve("App.java")),
                "a rejected patch leaves the disk as it was before staging");
    }

    @Test
    @DisplayName("fix loop end-to-end: fail -> stage a fix -> pass -> VALIDATED, budget stays consumed")
    void fixLoopEndToEnd() throws Exception {
        // a command that "fails" - we simulate the fix by switching the session's
        // referee: stage first with a failing session, then verify with a passing one
        session = session(List.of("ls", "/definitely-not-there-xyz"), 3);
        runTests = session.runTestsTool();
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"v1\"}"));

        runTests.execute(null);                          // failure #1
        assertEquals(1, session.failedTestRuns());
        assertEquals(Patch.PatchStatus.DRAFT, session.activePatch().orElseThrow().status());

        // the "fix": re-stage, then a passing referee (same store - same session object
        // is not possible, so we rebuild with the same workspace and re-attach state)
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"v2\"}"));

        // simulate the fixed suite passing via a second session on the same disk state
        CodingSession passing = session("Tests run: 1, Failures: 0", 3);
        passing.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"v2\"}"));
        Tool passingTests = passing.runTestsTool();
        passingTests.execute(null);

        assertEquals(Patch.PatchStatus.VALIDATED, passing.activePatch().orElseThrow().status());
        assertEquals(0, passing.failedTestRuns(), "passing consumed no budget");
    }

    // ============ Human gates & the patch state machine ============

    @Test
    @DisplayName("reviewPatch renders the unified diff for the human reviewer")
    void reviewPatch() throws Exception {
        session = session("ok", 3);
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"class App { int x; }\"}"));

        String review = session.reviewPatch();

        assertTrue(review.contains("1 file(s)"), review);
        assertTrue(review.contains("+++ App.java"), review);
        assertTrue(review.contains("+class App"), review);
    }

    @Test
    @DisplayName("reviewPatch on an empty store fails fast")
    void reviewPatchEmpty() {
        session = session("ok", 3);
        assertThrows(IllegalArgumentException.class, () -> session.reviewPatch());
    }

    @Test
    @DisplayName("approveAndApply writes the batch to disk - the only real write point")
    void approveAndApply() throws Exception {
        session = session("ok", 3);
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"class App { int x; }\"}"));
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"New.java\",\"content\":\"class New {}\"}"));

        ApplyResult result = session.approveAndApply();

        assertInstanceOf(ApplyResult.Applied.class, result);
        assertEquals(2, ((ApplyResult.Applied) result).filesWritten());
        assertEquals("class App { int x; }", Files.readString(root.resolve("App.java")));
        assertEquals("class New {}", Files.readString(root.resolve("New.java")));
        assertTrue(session.activePatch().isEmpty(), "patch closed after apply");
    }

    @Test
    @DisplayName("rejectPatch: REJECTED terminal state, disk untouched")
    void rejectPatch() throws Exception {
        session = session("ok", 3);
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"would-be change\"}"));

        Patch rejected = session.rejectPatch();

        assertEquals(Patch.PatchStatus.REJECTED, rejected.status());
        assertEquals("class App {}\n", Files.readString(root.resolve("App.java")));
        assertTrue(session.activePatch().isEmpty());
    }

    @Test
    @DisplayName("discardPatch: DISCARDED terminal state, disk untouched")
    void discardPatch() throws Exception {
        session = session("ok", 3);
        session.writeFileTool().execute(mapper.readTree(
                "{\"path\":\"New.java\",\"content\":\"never created\"}"));

        Patch discarded = session.discardPatch();

        assertEquals(Patch.PatchStatus.DISCARDED, discarded.status());
        assertFalse(Files.exists(root.resolve("New.java")));
    }

    // ============ Tool factories & builder ============

    @Test
    @DisplayName("tool factories produce the five tools with honest metadata")
    void toolFactories() {
        session = session("ok", 3);

        assertEquals("read_file", session.readFileTool().getName());
        assertEquals("list_files", session.listFilesTool().getName());
        assertEquals("write_file", session.writeFileTool().getName());
        assertEquals("run_command", session.runCommandTool().getName());
        Tool tests = session.runTestsTool();
        assertEquals("run_tests", tests.getName());
        assertTrue(tests.getDescription().contains("Fix budget"), tests.getDescription());
        assertTrue(tests.getDescription().contains("3"), tests.getDescription());
    }

    @Test
    @DisplayName("builder validation: missing required parts fail fast")
    void builderValidation() {
        assertThrows(NullPointerException.class, () -> CodingSession.builder().build());
        assertThrows(NullPointerException.class, () -> CodingSession.builder()
                .workspace(Workspace.open(root)).build());
        assertThrows(IllegalArgumentException.class, () -> CodingSession.builder()
                .workspace(Workspace.open(root))
                .whitelist(CommandWhitelist.builder().rule("echo").build())
                .runner(new CommandRunner(root))
                .testCommand(List.of())
                .build());
    }

    @Test
    @DisplayName("whitelist rejection of the test command does not consume fix budget")
    void whitelistRejectionNoBudget() {
        session = CodingSession.builder()
                .workspace(Workspace.open(root))
                .whitelist(CommandWhitelist.builder().rule("mvn").build())   // echo not granted
                .runner(new CommandRunner(root))
                .testCommand(List.of("echo", "ok"))
                .build();
        runTests = session.runTestsTool();

        String result = runTests.execute(null);

        assertTrue(result.startsWith("[REJECTED]"), result);
        assertEquals(0, session.failedTestRuns(), "the command never ran: no budget consumed");
    }
}
