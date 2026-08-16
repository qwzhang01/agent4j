package com.seven.agent.core.tool;

import com.seven.agent.core.model.ToolCall;

/**
 * Executes tool calls and handles errors.
 * <p>
 * Separated from ToolRegistry because execution concerns are different:
 * - Timeout enforcement
 * - Error wrapping (tool errors must become text for the model, not exceptions)
 * - Audit logging (stage 9)
 * - Policy checks (stage 9)
 * - Sandbox execution (stage 4)
 */
public interface ToolExecutor {

    /**
     * Execute a tool call.
     *
     * @param toolCall the tool call from the model
     * @return result text (sent back to the model)
     */
    String execute(ToolCall toolCall);
}
