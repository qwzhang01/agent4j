package io.github.qwzhang01.agent.core.run;

/**
 * Caller identity attached to a {@link RunContext} (Stage 1.1).
 * <p>
 * Free of credentials by design: this is the display/audit identity (who
 * the human or system actor is), NOT a token. Auth remains the host's
 * entry-boundary concern; downstream boundaries only read the resolved
 * identity, they never authenticate.
 */
public record RunIdentity(
        String principal,
        String displayName,
        String authMethod
) {

    public static RunIdentity ofPrincipal(String principal) {
        return new RunIdentity(principal, null, null);
    }
}
