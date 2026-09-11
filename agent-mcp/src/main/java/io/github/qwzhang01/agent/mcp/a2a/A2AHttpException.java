package io.github.qwzhang01.agent.mcp.a2a;

/**
 * Unchecked failure talking to an A2A endpoint over HTTP: transport errors
 * (connect refused, timeout, non-200, invalid JSON) or a JSON-RPC error
 * envelope (e.g. -32601 method not found, -32001 task not found).
 * <p>
 * Distinct from task failure: a task that RAN and failed comes back as a
 * task with {@link A2ATaskStatus#FAILED} (callers of
 * {@link A2AClient#sendTask} see {@link IllegalStateException}, same as
 * {@link InProcessA2AClient}); this exception means we never got a task
 * answer at all -- the call itself broke.
 */
public class A2AHttpException extends RuntimeException {

    /** Sentinel code for failures that are not a JSON-RPC error from the peer. */
    public static final int TRANSPORT = -1;

    private final int code;

    public A2AHttpException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** JSON-RPC error code from the peer, or {@link #TRANSPORT}. */
    public int code() {
        return code;
    }
}
