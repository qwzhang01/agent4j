package io.github.qwzhang01.agent.coding.patch;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;

import java.util.Objects;

/**
 * {@code write_file} - the Coding Agent's write handle (Stage 17 M17.2, blueprint D1).
 * <p>
 * This tool <b>does not write to disk</b>: it stages a {@link FileChange} in the
 * {@link PatchStore}. The confirmation text says so explicitly ("Nothing written to disk
 * yet") so the model knows applying is a separate, approved action. Re-staging the same
 * path replaces the previous staged content - the natural shape of a fix loop.
 * <p>
 * Governance note (blueprint D8): staging has no real disk side effect, so this tool is
 * a natural {@code AUTO} candidate; the human gate sits at apply time, not here.
 */
public final class WriteFileTool implements Tool {

    public static final String NAME = "write_file";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "File path relative to the workspace root, e.g. 'src/main/java/App.java'"
                },
                "content": {
                  "type": "string",
                  "description": "The FULL new content of the file (replaces any existing content)"
                }
              },
              "required": ["path", "content"]
            }
            """;

    private final PatchStore store;

    public WriteFileTool(PatchStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Stage a file change in the patch store. Nothing is written to disk until "
                + "the patch is applied - use run_tests to verify staged changes and let the "
                + "reviewer apply them. Staging the same path again replaces the previous "
                + "staged content.";
    }

    @Override
    public String getParametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        if (arguments == null || !arguments.hasNonNull("path") || !arguments.hasNonNull("content")) {
            throw new ToolException("write_file requires non-null 'path' and 'content' arguments");
        }
        String path = arguments.get("path").asText();
        if (path.isBlank()) {
            throw new ToolException("'path' must not be blank");
        }
        String content = arguments.get("content").asText();

        FileChange change;
        try {
            change = store.stage(path, content);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        int stagedCount = store.snapshot().map(Patch::size).orElse(0);
        return "staged: " + change.path() + " (kind=" + change.kind() + "). "
                + "Nothing written to disk yet. " + stagedCount + " file(s) staged.";
    }
}
