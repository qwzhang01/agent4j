package io.github.qwzhang01.agent.coding.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;

import java.util.Objects;

/**
 * {@code read_file} - the Coding Agent's read handle on the workspace
 * (Stage 17 M17.1, blueprint: "reading is a privilege too").
 * <p>
 * A thin wrapper over {@link Workspace#readFile(String)}: the safety layers (path
 * escape, deny policy, symlink escape, byte budget) all live in the workspace; the tool
 * only translates {@link IllegalArgumentException}s into {@link ToolException}s so the
 * model receives a readable observation and can recover (pick another path, give up).
 * <p>
 * Governance note (blueprint D8): this tool is side-effect free and therefore a natural
 * {@code AUTO} candidate in the Stage 9 permission chain.
 */
public final class ReadFileTool implements Tool {

    public static final String NAME = "read_file";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "File path relative to the workspace root, e.g. 'src/main/java/App.java'"
                }
              },
              "required": ["path"]
            }
            """;

    private final Workspace workspace;

    public ReadFileTool(Workspace workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Read a text file from the workspace. The path must be relative to the "
                + "workspace root. Deny-listed paths (e.g. .git internals, .env secrets) "
                + "are rejected, and files above the size limit are returned truncated "
                + "with an explicit marker.";
    }

    @Override
    public String getParametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        if (arguments == null || !arguments.hasNonNull("path")) {
            throw new ToolException("read_file requires a non-null 'path' argument");
        }
        String path = arguments.get("path").asText();
        if (path.isBlank()) {
            throw new ToolException("'path' must not be blank");
        }
        try {
            return workspace.readFile(path);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
