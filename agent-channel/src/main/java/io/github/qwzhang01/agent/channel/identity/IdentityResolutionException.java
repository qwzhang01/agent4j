package io.github.qwzhang01.agent.channel.identity;

/**
 * Thrown when identity resolution fails closed (Stage 12 D4).
 * <p>
 * Fail-closed semantics: an identity problem can never be "degraded
 * through" - an unknown agent, an expired account, a non-member user, or
 * an empty permission intersection all refuse to start the run. The
 * caller (assembly layer) decides how to surface this to the user; the
 * framework refuses to proceed silently.
 * <p>
 * The full {@link IdentityDecision} (including the denial reason and both
 * permission sets) travels with the exception, so callers and tests can
 * assert on it without string matching.
 */
public class IdentityResolutionException extends RuntimeException {

    private final transient IdentityDecision decision;

    public IdentityResolutionException(IdentityDecision decision) {
        super(formatMessage(decision));
        this.decision = decision;
    }

    /**
     * The denial decision that caused this exception.
     */
    public IdentityDecision decision() {
        return decision;
    }

    /**
     * Convenience accessor for the denial reason.
     */
    public IdentityDecision.DenialReason reason() {
        return decision.reason();
    }

    private static String formatMessage(IdentityDecision d) {
        return "identity resolution denied [" + d.reason() + "]"
                + " agent=" + d.agentId()
                + " user=" + d.userId()
                + " channel=" + d.channelId()
                + " granted=" + d.granted()
                + " role=" + d.role();
    }
}
