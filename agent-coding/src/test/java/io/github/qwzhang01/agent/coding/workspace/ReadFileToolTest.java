package io.github.qwzhang01.agent.coding.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.tool.ToolException;
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
 * Stage 17 M17.1: the read tool is a thin, honest translator - workspace
 * {@link IllegalArgumentException}s become {@link ToolException}s the model can read
 * and recover from; content flows through verbatim.
 */
class ReadFileToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private ReadFileTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(root.resolve("App.java"), "class App {}");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[core]");
        tool = new ReadFileTool(Workspace.open(root));
    }

    @Test
    @DisplayName("reading an existing file returns its content verbatim")
    void readsFile() throws Exception {
        String result = tool.execute(mapper.readTree("{\"path\":\"App.java\"}"));
        assertEquals("class App {}", result);
    }

    @Test
    @DisplayName("missing/blank path argument is a ToolException, not a crash")
    void missingArgumentsRejected() throws Exception {
        assertThrows(ToolException.class, () -> tool.execute(null));
        assertThrows(ToolException.class, () -> tool.execute(mapper.createObjectNode()));
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\"  \"}")));
    }

    @Test
    @DisplayName("path escape surfaces as a readable ToolException")
    void escapeRejected() throws Exception {
        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\"../outside.txt\"}")));
        assertTrue(e.getMessage().contains("escapes"), e.getMessage());
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\"/etc/passwd\"}")));
    }

    @Test
    @DisplayName("deny-listed path surfaces as a readable ToolException")
    void deniedRejected() throws Exception {
        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\".git/config\"}")));
        assertTrue(e.getMessage().contains("denied"), e.getMessage());
    }

    @Test
    @DisplayName("missing file surfaces as a readable ToolException mentioning the path")
    void missingFileRejected() throws Exception {
        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\"nope.java\"}")));
        assertTrue(e.getMessage().contains("nope.java"), e.getMessage());
    }

    @Test
    @DisplayName("tool metadata is model-facing and honest")
    void metadata() {
        assertEquals("read_file", tool.getName());
        assertTrue(tool.getDescription().contains("workspace"));
        assertTrue(tool.getParametersSchema().contains("\"path\""));
        assertTrue(tool.getParametersSchema().contains("\"required\""));
    }
}
