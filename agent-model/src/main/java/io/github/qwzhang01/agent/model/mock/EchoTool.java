package io.github.qwzhang01.agent.model.mock;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;

/**
 * A simple echo tool for testing.
 * Returns whatever input it receives.
 */
public class EchoTool implements Tool {

    @Override
    public String getName() {
        return "echo";
    }

    @Override
    public String getDescription() {
        return "Echoes back the input text. Useful for testing the tool calling pipeline.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "input": {
                      "type": "string",
                      "description": "The text to echo back"
                    }
                  },
                  "required": ["input"]
                }""";
    }

    @Override
    public String execute(JsonNode arguments) {
        String input = arguments != null && arguments.has("input") ? arguments.get("input").asText() : "(empty)";
        return "Echo: " + input;
    }
}
