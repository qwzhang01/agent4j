package io.github.qwzhang01.agent.mcp.a2a;

/**
 * Thrown by an {@link io.github.qwzhang01.agent.core.agent.Agent} to pause an A2A
 * task as {@link A2ATaskStatus#INPUT_REQUIRED}. The message is what the caller
 * sees on the wire. The server keeps {@code AgentState} so
 * {@code message.taskId} can continue the same conversation.
 */
public class A2AInputRequiredException extends RuntimeException {

    public A2AInputRequiredException(String messageForCaller) {
        super(messageForCaller == null || messageForCaller.isBlank()
                ? "input required" : messageForCaller);
    }
}
