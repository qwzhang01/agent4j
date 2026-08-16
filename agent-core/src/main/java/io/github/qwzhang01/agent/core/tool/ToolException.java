package io.github.qwzhang01.agent.core.tool;

/**
 * Exception thrown when a tool execution fails.
 * <p>
 * The error message will be sent back to the model so it can decide
 * how to recover (retry with different args, try another tool, or give up).
 */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
