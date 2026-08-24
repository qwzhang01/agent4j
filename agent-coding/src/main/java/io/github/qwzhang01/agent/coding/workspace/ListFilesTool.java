package io.github.qwzhang01.agent.coding.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;

import java.util.Objects;

/**
 * {@code list_files} - the Coding Agent's directory view on the workspace
 * (Stage 17 M17.1).
 * <p>
 * Wraps {@link Workspace#listTree(String, int)}: deterministic sorted listing,
 * deny-listed entries and symlinks invisible, depth/entry budgets enforced by the
 * workspace policy. Output paths are workspace-relative so the model can feed them
 * straight back into {@code read_file}.
 * <p>
 * Governance note (blueprint D8): side-effect free, natural {@code AUTO} candidate.
 */
public final class ListFilesTool implements Tool {

    public static final String NAME = "list_files";

    private static final int DEFAULT_DEPTH = 2;

    private final Workspace workspace;

    public ListFilesTool(Workspace workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "List files and directories under a path in the workspace, one "
                + "workspace-relative path per line (directories end with '/'), sorted. "
                + "Deny-listed entries and symbolic links are not shown. "
                + "max_depth must be between 0 and " + workspace.policy().maxDepth()
                + " (default " + DEFAULT_DEPTH + ").";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "Directory path relative to the workspace root (default: the root itself)"
                    },
                    "max_depth": {
                      "type": "integer",
                      "description": "Maximum recursion depth, 0 lists only the direct children (default 2)"
                    }
                  }
                }
                """;
    }

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        String path = (arguments == null || !arguments.hasNonNull("path"))
                ? "" : arguments.get("path").asText();
        int maxDepth = DEFAULT_DEPTH;
        if (arguments != null && arguments.hasNonNull("max_depth")) {
            JsonNode depthNode = arguments.get("max_depth");
            if (!depthNode.canConvertToInt()) {
                throw new ToolException("'max_depth' must be an integer");
            }
            maxDepth = depthNode.asInt();
            if (maxDepth < 0) {
                throw new ToolException("'max_depth' must not be negative: " + maxDepth);
            }
            if (maxDepth > workspace.policy().maxDepth()) {
                throw new ToolException("'max_depth' " + maxDepth + " exceeds the policy limit "
                        + workspace.policy().maxDepth());
            }
        }
        try {
            return workspace.listTree(path, maxDepth);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
