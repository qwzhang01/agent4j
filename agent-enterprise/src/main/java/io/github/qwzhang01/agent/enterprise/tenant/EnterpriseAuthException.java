package io.github.qwzhang01.agent.enterprise.tenant;

/**
 * Authentication / registration failure (Stage 15 M15.1).
 * <p>
 * Fail-closed philosophy (same family as Stage 12 IdentityResolutionException):
 * every rejected login or registration throws with an evidence-carrying
 * message instead of returning a degraded context. "Who was rejected and why"
 * is itself a security signal.
 */
public class EnterpriseAuthException extends RuntimeException {

    public EnterpriseAuthException(String message) {
        super(message);
    }
}
