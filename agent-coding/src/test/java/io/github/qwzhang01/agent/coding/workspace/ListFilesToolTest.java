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
 * Stage 17 M17.1: the list tool - defaults, depth validation against the policy
 * limit, and policy-filtered deterministic output.
 */
class ListFilesToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private ListFilesTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}");
        Files.writeString(root.resolve("README.md"), "# project");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[core]");
        tool = new ListFilesTool(Workspace.open(root));
    }

    @Test
    @DisplayName("no arguments: default root path and default depth 2")
    void defaults() throws Exception {
        String result = tool.execute(mapper.createObjectNode());

        // depth 2 from root: README.md, src/, src/main/, src/main/java/
        // (the java/ directory is listed, but its contents need depth 3)
        assertEquals("README.md\nsrc/\nsrc/main/\nsrc/main/java/\n", result);
        assertTrue(!result.contains(".git"), "denied entries must be invisible");
    }

    @Test
    @DisplayName("explicit path and max_depth are honored; output paths stay workspace-relative")
    void explicitPathAndDepth() throws Exception {
        String result = tool.execute(
                mapper.readTree("{\"path\":\"src/main\",\"max_depth\":1}"));
        assertEquals("src/main/java/\nsrc/main/java/App.java\n", result);
    }

    @Test
    @DisplayName("max_depth 0 lists only the direct children")
    void depthZero() throws Exception {
        String result = tool.execute(mapper.readTree("{\"max_depth\":0}"));
        assertEquals("README.md\nsrc/\n", result);
    }

    @Test
    @DisplayName("max_depth above the policy limit is a readable rejection with the range")
    void depthAbovePolicyRejected() throws Exception {
        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"max_depth\":5}")));
        assertTrue(e.getMessage().contains("policy limit"), e.getMessage());
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"max_depth\":-1}")));
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"max_depth\":\"two\"}")));
    }

    @Test
    @DisplayName("non-directory path is a readable rejection")
    void notADirectory() throws Exception {
        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\"README.md\"}")));
        assertTrue(e.getMessage().contains("not a directory"), e.getMessage());
    }

    @Test
    @DisplayName("tool metadata is model-facing and mentions the depth limit")
    void metadata() {
        assertEquals("list_files", tool.getName());
        assertTrue(tool.getDescription().contains("max_depth"), tool.getDescription());
        assertTrue(tool.getDescription().contains("4"), "policy limit is stated: " + tool.getDescription());
        assertTrue(tool.getParametersSchema().contains("\"path\""));
    }
}
