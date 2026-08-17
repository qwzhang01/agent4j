package io.github.qwzhang01.agent.sandbox.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.sandbox.Sandbox;
import io.github.qwzhang01.agent.sandbox.SandboxResult;

/**
 * Tool wrapper for Sandbox, allowing the model to execute code.
 * <p>
 * The model calls this tool to run Java code in the sandbox:
 * {
 * "class_name": "Generated",
 * "code": "public class Generated { public static String run() { return \"hello\"; } }"
 * }
 * <p>
 * Returns the execution result (stdout / error / blocked).
 */
public class SandboxTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Sandbox sandbox;

    public SandboxTool(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String getName() {
        return "sandbox_execute";
    }

    @Override
    public String getDescription() {
        return "Execute Java code in a sandbox. The code must define a class with a "
                + "public static String run() method. "
                + "File system, network, and process execution are blocked.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "name": "sandbox_execute",
                    "description": "Execute Java code in a sandbox",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "class_name": {
                                "type": "string",
                                "description": "The class name (must match the public class in the code)"
                            },
                            "code": {
                                "type": "string",
                                "description": "The Java source code"
                            }
                        },
                        "required": ["class_name", "code"]
                    }
                }
                """;
    }

    @Override
    public String execute(JsonNode arguments) {
        String className = arguments.path("class_name").asText("");
        String code = arguments.path("code").asText("");

        ObjectNode result = mapper.createObjectNode();

        if (className.isBlank() || code.isBlank()) {
            result.put("success", false);
            result.put("error", "class_name and code are required");
            return result.toString();
        }

        SandboxResult sandboxResult = sandbox.execute(className, code);

        result.put("success", sandboxResult.success());
        if (sandboxResult.stdout() != null && !sandboxResult.stdout().isBlank()) {
            result.put("stdout", sandboxResult.stdout());
        }
        if (sandboxResult.stderr() != null && !sandboxResult.stderr().isBlank()) {
            result.put("stderr", sandboxResult.stderr());
        }
        if (sandboxResult.error() != null) {
            result.put("error", sandboxResult.error());
        }
        result.put("timedOut", sandboxResult.timedOut());

        return result.toString();
    }
}
