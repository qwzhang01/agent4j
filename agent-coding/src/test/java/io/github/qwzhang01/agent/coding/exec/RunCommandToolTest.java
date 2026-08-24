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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.3: the command tool - whitelist gate, JSON result, and the D2 core
 * proof: injection syntax is inert because there is no shell to interpret it.
 */
class RunCommandToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private RunCommandTool tool;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("project"));
        CommandWhitelist whitelist = CommandWhitelist.builder()
                .rule("echo")
                .rule("mvn", "test")
                .build();
        tool = new RunCommandTool(whitelist, new CommandRunner(root));
    }

    @Test
    @DisplayName("a whitelisted command runs and returns structured JSON")
    void whitelistedRuns() throws Exception {
        String result = tool.execute(mapper.readTree("{\"command\":[\"echo\",\"hi\"]}"));

        JsonNode json = mapper.readTree(result);
        assertTrue(json.get("success").asBoolean());
        assertEquals(0, json.get("exit_code").asInt());
        assertEquals("hi\n", json.get("stdout").asText());
    }

    @Test
    @DisplayName("a non-whitelisted command is [REJECTED] with the allowed list - readable and recoverable")
    void rejectedWithAllowedList() throws Exception {
        String result = tool.execute(
                mapper.readTree("{\"command\":[\"curl\",\"http://evil.example\"]}"));

        assertTrue(result.startsWith("[REJECTED]"), result);
        assertTrue(result.contains("curl"), result);
        assertTrue(result.contains("echo"), "allowed commands are listed: " + result);
        assertTrue(result.contains("mvn test"), result);
    }

    @Test
    @DisplayName("argv[1] out of the granted prefix is rejected too (mvn clean vs mvn test)")
    void prefixScoping() throws Exception {
        String result = tool.execute(mapper.readTree("{\"command\":[\"mvn\",\"clean\"]}"));
        assertTrue(result.startsWith("[REJECTED]"), result);
    }

    @Test
    @DisplayName("D2 core proof: injection syntax is an inert argument - no shell interprets it")
    void injectionIsInert() throws Exception {
        // a marker file the "injected" command would have deleted
        Path marker = Files.writeString(root.resolve("marker.txt"), "still here");

        // echo is whitelisted and harmless; the payload is just an argument to it
        String result = tool.execute(mapper.readTree(
                "{\"command\":[\"echo\",\"x; rm marker.txt\"]}"));

        JsonNode json = mapper.readTree(result);
        assertTrue(json.get("success").asBoolean());
        // the payload was printed verbatim - nobody interpreted it
        assertEquals("x; rm marker.txt\n", json.get("stdout").asText());
        // and the marker file is untouched
        assertEquals("still here", Files.readString(marker));
    }

    @Test
    @DisplayName("malformed arguments are ToolExceptions: missing, non-array, non-string, empty")
    void malformedArguments() throws Exception {
        assertThrows(Exception.class, () -> tool.execute(null));
        assertThrows(Exception.class, () -> tool.execute(mapper.createObjectNode()));
        assertThrows(Exception.class,
                () -> tool.execute(mapper.readTree("{\"command\":\"echo hi\"}")));
        assertThrows(Exception.class,
                () -> tool.execute(mapper.readTree("{\"command\":[]}")));
    }

    @Test
    @DisplayName("non-zero exit surfaces honestly in the JSON")
    void nonZeroExitHonest() throws Exception {
        CommandWhitelist whitelist = CommandWhitelist.builder().rule("ls").build();
        RunCommandTool lsTool = new RunCommandTool(whitelist, new CommandRunner(root));

        String result = lsTool.execute(mapper.readTree(
                "{\"command\":[\"ls\",\"/definitely-not-there-xyz\"]}"));

        JsonNode json = mapper.readTree(result);
        assertTrue(!json.get("success").asBoolean());
        assertTrue(json.get("exit_code").asInt() != 0);
    }

    @Test
    @DisplayName("tool metadata states the whitelist and the no-shell semantics")
    void metadata() {
        assertEquals("run_command", tool.getName());
        assertTrue(tool.getDescription().contains("echo"), tool.getDescription());
        assertTrue(tool.getDescription().contains("no shell"), tool.getDescription());
        assertTrue(tool.getParametersSchema().contains("\"command\""));
    }
}
