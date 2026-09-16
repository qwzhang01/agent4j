package io.github.qwzhang01.agent.core.tool;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of ToolExecutor.
 * <p>
 * Responsibilities (current stage):
 * - Look up tool in registry
 * - Execute tool
 * - Wrap errors as text (so the model can decide how to recover)
 * <p>
 * Future stages will add:
 * - Timeout enforcement (stage 6)
 * - Policy check before execution (stage 9)
 * - Audit logging (stage 9)
 * - Sandbox execution (stage 4)
 */
public class DefaultToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

    private final ToolRegistry registry;

    public DefaultToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String execute(ToolCall toolCall) {
        return execute(toolCall, null);
    }

    /**
     * Context-aware execution (Stage 1.2): forwards the run context to the
     * tool boundary so tools can see tenant / user / budget / cancellation.
     * Tools that do not override the ctx overload fall back to the legacy
     * method via the {@link Tool} default implementation.
     */
    @Override
    public String execute(ToolCall toolCall, RunContext ctx) {
        var toolOpt = registry.getTool(toolCall.name());
        if (toolOpt.isEmpty()) {
            String msg = "Tool not found: " + toolCall.name();
            log.warn(msg);
            return "[ERROR] " + msg;
        }

        Tool tool = toolOpt.get();
        try {
            log.debug("Executing tool: {} with args: {}", toolCall.name(), toolCall.arguments());
            String result = tool.execute(toolCall.arguments(), ctx);
            log.debug("Tool {} returned: {}", toolCall.name(),
                    result != null && result.length() > 200 ? result.substring(0, 200) + "..." : result);
            return result;
        } catch (ToolException e) {
            log.error("Tool {} failed: {}", toolCall.name(), e.getMessage());
            return "[ERROR] Tool '" + toolCall.name() + "' failed: " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error executing tool {}", toolCall.name(), e);
            return "[ERROR] Unexpected error in tool '" + toolCall.name() + "': " + e.getMessage();
        }
    }
}
