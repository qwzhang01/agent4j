package io.github.qwzhang01.agent.mcp.a2a;

/**
 * Capability flags from the spec's AgentCard dialect.
 * <p>
 * v1 declares all of them false, honestly: no {@code message/stream} (SSE),
 * no push notifications, no state transition history. The flags exist so a
 * spec-compliant client can read our card and know what NOT to try, and so
 * flipping one on later is a one-line change instead of a wire redesign.
 *
 * @param streaming              supports message/stream (SSE)
 * @param pushNotifications      can push task updates without polling
 * @param stateTransitionHistory exposes task history via resubscription
 */
public record A2ACapabilities(boolean streaming, boolean pushNotifications,
                              boolean stateTransitionHistory) {

    /** The honest v1 posture: none of the advanced capabilities. */
    public static A2ACapabilities none() {
        return new A2ACapabilities(false, false, false);
    }
}
