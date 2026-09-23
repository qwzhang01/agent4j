package io.github.qwzhang01.agent.core.tool;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.RunContext;

/**
 * Executes tool calls and handles errors.
 * <p>
 * Separated from ToolRegistry because execution concerns are different:
 * - Timeout enforcement
 * - Error wrapping (tool errors must become text for the model, not exceptions)
 * - Audit logging
 * - Policy checks
 * - Sandbox execution
 * <p>
 *  the ctx-aware overload carries identity, budget and idempotency
 * scope to the tool boundary. Legacy single-arg execution stays frozen.
 */
public interface ToolExecutor {

    /**
     * Execute a tool call.
     *
     * @param toolCall the tool call from the model
     * @return result text (sent back to the model)
     */
    String execute(ToolCall toolCall);

    /**
     * Execute a tool call with the run context .
     * <p>
     * Default: delegate to the legacy method (context not consumed). The
     * context gives governance decorators access to runId/tenant/identity/
     * budget/idempotency scope; a bare executor may ignore it.
     *
     * @param toolCall the tool call from the model
     * @param ctx the run context (may be null on the legacy path)
     * @return result text (sent back to the model)
     */
    default String execute(ToolCall toolCall, RunContext ctx) {
        return execute(toolCall);
    }
}
