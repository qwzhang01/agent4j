package io.github.qwzhang01.agent.coding.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.sandbox.SandboxResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code run_command} - the model's command handle (Stage 17 M17.3).
 * <p>
 * Gate layout (blueprint D2): the governance chain (Stage 9, plugged at assembly time,
 * M17.5) decides IF the tool may run at all; this tool's own whitelist decides whether
 * the <b>argument</b> - the specific argv - is legal; the runner enforces time/space
 * budgets. Any single gate failing is not an accident.
 * <p>
 * The argv is passed as a JSON array and executed <b>without a shell</b>: injection
 * syntax in arguments is inert by construction. Whitelist rejections return a
 * {@code [REJECTED]} text with the allowed commands listed, so the model can read,
 * understand, and choose a legal path instead of guessing.
 */
public final class RunCommandTool implements Tool {

    public static final String NAME = "run_command";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "The command to run as an argv array, e.g. ['mvn','test','-q']. No shell is involved; only whitelisted command prefixes are allowed."
                }
              },
              "required": ["command"]
            }
            """;

    private final CommandWhitelist whitelist;
    private final CommandRunner runner;

    public RunCommandTool(CommandWhitelist whitelist, CommandRunner runner) {
        this.whitelist = Objects.requireNonNull(whitelist, "whitelist must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Run a whitelisted command in the workspace (no shell). Allowed command "
                + "prefixes: " + whitelist.summary() + ". Anything else is rejected. "
                + "Output is truncated with a marker if too large; the command is killed "
                + "on timeout.";
    }

    @Override
    public String getParametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        List<String> argv = parseArgv(arguments);

        CommandWhitelist.CheckResult check = whitelist.check(argv);
        if (!check.allowed()) {
            return "[REJECTED] " + check.reason() + ". Allowed command prefixes: "
                    + whitelist.summary();
        }

        SandboxResult result = runner.run(argv);
        return render(result);
    }

    private static List<String> parseArgv(JsonNode arguments) throws ToolException {
        if (arguments == null || !arguments.hasNonNull("command")) {
            throw new ToolException("run_command requires a non-null 'command' array argument");
        }
        JsonNode command = arguments.get("command");
        if (!command.isArray()) {
            throw new ToolException("'command' must be an array of strings, e.g. ['mvn','test']");
        }
        List<String> argv = new ArrayList<>();
        for (JsonNode element : command) {
            if (element == null || !element.isTextual()) {
                throw new ToolException("'command' array must contain only strings");
            }
            argv.add(element.asText());
        }
        if (argv.isEmpty()) {
            throw new ToolException("'command' array must not be empty");
        }
        return argv;
    }

    static String render(SandboxResult result) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("success", result.success());
        json.put("exit_code", result.exitCode());
        json.put("timed_out", result.timedOut());
        if (result.stdout() != null && !result.stdout().isBlank()) {
            json.put("stdout", result.stdout());
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            json.put("stderr", result.stderr());
        }
        if (result.error() != null) {
            json.put("error", result.error());
        }
        return json.toString();
    }
}
