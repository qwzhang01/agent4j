package io.github.qwzhang01.agent.coding.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.coding.workspace.Workspace;
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
 * Stage 17 M17.2: the write tool stages, never writes - and says so in its
 * confirmation so the model knows applying is a separate, approved action.
 */
class WriteFileToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private WriteFileTool tool;
    private PatchStore store;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(root.resolve("App.java"), "original");
        store = new PatchStore(Workspace.open(root));
        tool = new WriteFileTool(store);
    }

    @Test
    @DisplayName("staging an existing file confirms MODIFY, disk-untouched, and staged count")
    void stageModify() throws Exception {
        String result = tool.execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"new content\"}"));

        assertTrue(result.contains("staged: App.java"), result);
        assertTrue(result.contains("kind=MODIFY"), result);
        assertTrue(result.contains("Nothing written to disk yet"), result);
        assertTrue(result.contains("1 file(s) staged"), result);
        assertEquals("original", Files.readString(root.resolve("App.java")),
                "the tool must not write to disk");
    }

    @Test
    @DisplayName("staging an absent file confirms CREATE and grows the staged count")
    void stageCreate() throws Exception {
        tool.execute(mapper.readTree("{\"path\":\"App.java\",\"content\":\"x\"}"));
        String second = tool.execute(mapper.readTree(
                "{\"path\":\"New.java\",\"content\":\"y\"}"));

        assertTrue(second.contains("kind=CREATE"), second);
        assertTrue(second.contains("2 file(s) staged"), second);
        assertFalseOnDisk("New.java");
    }

    @Test
    @DisplayName("re-staging the same path keeps the staged count stable (replace, not stack)")
    void reStageReplaces() throws Exception {
        tool.execute(mapper.readTree("{\"path\":\"A.java\",\"content\":\"1\"}"));
        String second = tool.execute(mapper.readTree("{\"path\":\"A.java\",\"content\":\"2\"}"));

        assertTrue(second.contains("1 file(s) staged"), second);
        assertEquals("2", store.snapshot().orElseThrow().changes().get(0).newContent());
    }

    @Test
    @DisplayName("missing/blank arguments are ToolExceptions")
    void missingArguments() throws Exception {
        assertThrows(ToolException.class, () -> tool.execute(null));
        assertThrows(ToolException.class, () -> tool.execute(mapper.createObjectNode()));
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\"A.java\"}")));
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\"  \",\"content\":\"x\"}")));
    }

    @Test
    @DisplayName("escape and deny paths surface as readable ToolExceptions")
    void pathSafety() throws Exception {
        ToolException escape = assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\"../evil.sh\",\"content\":\"x\"}")));
        assertTrue(escape.getMessage().contains("escapes"), escape.getMessage());

        ToolException deny = assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"path\":\".git/config\",\"content\":\"x\"}")));
        assertTrue(deny.getMessage().contains("denied"), deny.getMessage());
    }

    @Test
    @DisplayName("empty content is a legal staged write (empty file), null content is not")
    void emptyContent() throws Exception {
        String result = tool.execute(mapper.readTree(
                "{\"path\":\"App.java\",\"content\":\"\"}"));
        assertTrue(result.contains("kind=MODIFY"), result);
        assertEquals("", store.snapshot().orElseThrow().changes().get(0).newContent());
    }

    @Test
    @DisplayName("tool metadata is model-facing and mentions the staging semantics")
    void metadata() {
        assertEquals("write_file", tool.getName());
        assertTrue(tool.getDescription().contains("Nothing is written to disk"), tool.getDescription());
        assertTrue(tool.getParametersSchema().contains("\"content\""));
        assertTrue(tool.getParametersSchema().contains("\"required\""));
    }

    private void assertFalseOnDisk(String rel) {
        assertTrue(!Files.exists(root.resolve(rel)), rel + " must not exist on disk (staged only)");
    }
}
