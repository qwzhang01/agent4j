package io.github.qwzhang01.agent.examples.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.plugin.PluginContext;
import io.github.qwzhang01.agent.plugin.PluginDescriptor;
import io.github.qwzhang01.agent.plugin.ToolPlugin;

/**
 * Example ToolPlugin that registers a "calculate" tool.
 * <p>
 * Demonstrates a simple calculator tool loaded as a plugin.
 */
public class CalculatorToolPlugin implements ToolPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("calculator-tool", "1.0.0", "Simple arithmetic calculator");
    }

    @Override
    public void onLoad(PluginContext context) {
        context.getToolRegistry().register(new CalculatorTool());
    }

    @Override
    public void onUnload(PluginContext context) {
        context.getToolRegistry().unregister("calculate");
    }

    // ============ The actual tool ============

    public static class CalculatorTool implements Tool {

        @Override
        public String getName() {
            return "calculate";
        }

        @Override
        public String getDescription() {
            return "Perform basic arithmetic: add, subtract, multiply, divide";
        }

        @Override
        public String getParametersSchema() {
            return """
                    {
                        "name": "calculate",
                        "description": "Perform basic arithmetic",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "operator": {
                                    "type": "string",
                                    "enum": ["add", "subtract", "multiply", "divide"],
                                    "description": "The operation to perform"
                                },
                                "a": { "type": "number" },
                                "b": { "type": "number" }
                            },
                            "required": ["operator", "a", "b"]
                        }
                    }
                    """;
        }

        @Override
        public String execute(JsonNode arguments) {
            String op = arguments.path("operator").asText("add");
            double a = arguments.path("a").asDouble(0);
            double b = arguments.path("b").asDouble(0);

            return switch (op) {
                case "add" -> String.valueOf(a + b);
                case "subtract" -> String.valueOf(a - b);
                case "multiply" -> String.valueOf(a * b);
                case "divide" -> b != 0 ? String.valueOf(a / b) : "Error: division by zero";
                default -> "Error: unknown operator: " + op;
            };
        }
    }
}
