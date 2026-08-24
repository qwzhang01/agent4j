package io.github.qwzhang01.agent.coding.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.3 (+ M17.4 evolution): the fixed-referee tool - the test command is
 * assembly-injected and takes no arguments (D3: the referee cannot be chosen by the
 * refereed). {@code run()} returns the structured verdict for the session's fix-loop
 * wiring (the M17.3 onTestFailure listener was superseded by it).
 */
class RunTestsToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("project"));
    }

    @Test
    @DisplayName("a passing fixed command yields passed=true JSON")
    void passingRun() throws Exception {
        RunTestsTool tool = tool(List.of("echo", "Tests run: 2, Failures: 0"));

        String result = tool.execute(null);

        JsonNode json = mapper.readTree(result);
        assertTrue(json.get("passed").asBoolean());
        assertTrue(json.get("output_excerpt").asText().contains("Tests run: 2, Failures: 0"));
        assertTrue(json.get("duration_ms").asLong() >= 0);
    }

    @Test
    @DisplayName("a failing fixed command yields passed=false JSON")
    void failingRun() throws Exception {
        RunTestsTool tool = tool(List.of("ls", "/definitely-not-there-xyz"));

        String result = tool.execute(null);

        JsonNode json = mapper.readTree(result);
        assertFalse(json.get("passed").asBoolean());
        assertTrue(json.get("exit_code").asInt() != 0);
        assertTrue(json.get("output_excerpt").asText().contains("--- output tail ---"));
    }

    @Test
    @DisplayName("run() returns the structured verdict: failed runs are readable without JSON parsing")
    void runReturnsStructuredVerdict() {
        RunTestsTool tool = tool(List.of("ls", "/definitely-not-there-xyz"));

        TestResult verdict = tool.run(null);

        assertFalse(verdict.passed());
        assertTrue(verdict.exitCode() != 0);
        assertTrue(verdict.durationMs() >= 0);
        assertTrue(verdict.outputExcerpt().contains("--- output tail ---"));
    }

    @Test
    @DisplayName("a timed-out test run is an honest failure in the structured verdict")
    void timeoutInVerdict() {
        RunTestsTool tool = new RunTestsTool(List.of("sleep", "30"),
                CommandWhitelist.builder().rule("sleep").build(),
                new CommandRunner(root, Duration.ofMillis(300), 64 * 1024));

        TestResult verdict = tool.run(null);

        assertFalse(verdict.passed());
        assertTrue(verdict.timedOut());
        assertTrue(verdict.outputExcerpt().contains("[TIMED OUT]"));
    }

    @Test
    @DisplayName("the referee cannot be chosen by the refereed: arguments are ignored")
    void argumentsIgnored() throws Exception {
        // the model tries to smuggle its own command - the fixed referee runs anyway
        RunTestsTool tool = tool(List.of("echo", "fixed-referee"));

        String result = tool.execute(mapper.readTree(
                "{\"command\":[\"mvn\",\"test\",\"-DskipTests\"]}"));

        JsonNode json = mapper.readTree(result);
        assertTrue(json.get("passed").asBoolean());
        assertTrue(json.get("output_excerpt").asText().contains("fixed-referee"),
                "the injected command did not run: " + result);
    }

    @Test
    @DisplayName("a test command not granted in the whitelist is rejected (whitelist is the SSOT)")
    void testCommandMustBeWhitelisted() throws Exception {
        RunTestsTool tool = new RunTestsTool(List.of("echo", "hi"),
                CommandWhitelist.builder().rule("mvn").build(),   // echo NOT granted
                new CommandRunner(root));

        String result = tool.execute(null);

        assertTrue(result.startsWith("[REJECTED]"), result);
        assertTrue(result.contains("not whitelisted"), result);

        // the structured path exposes the same verdict as an Optional rejection
        assertTrue(tool.whitelistRejection().isPresent());
    }

    @Test
    @DisplayName("constructor guards and metadata")
    void guardsAndMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new RunTestsTool(List.of(),
                CommandWhitelist.builder().rule("echo").build(), new CommandRunner(root)));

        RunTestsTool tool = tool(List.of("echo", "ok"));
        assertEquals("run_tests", tool.getName());
        assertTrue(tool.getDescription().contains("echo ok"),
                "the fixed referee is stated: " + tool.getDescription());
        assertTrue(tool.getDescription().contains("cannot be modified"), tool.getDescription());
        assertNull(tool.getParametersSchema().lines()
                .filter(l -> l.contains("\"command\""))
                .findFirst().orElse(null), "no parameters: the referee takes no arguments");
    }

    // ============ Helpers ============

    private RunTestsTool tool(List<String> testCommand) {
        return new RunTestsTool(testCommand,
                CommandWhitelist.builder().rule("echo").rule("ls").rule("sleep").build(),
                new CommandRunner(root, Duration.ofSeconds(10), 64 * 1024));
    }
}
