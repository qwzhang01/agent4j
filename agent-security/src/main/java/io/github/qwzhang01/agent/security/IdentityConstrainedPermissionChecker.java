package io.github.qwzhang01.agent.security;

import java.util.Collection;
import java.util.Set;

/**
 * Stage 12 assembly bridge: identity capabilities ∩ {@link ToolPolicy}.
 * <p>
 * Lives in agent-security (not agent-channel) so channel stays free of a
 * hard security dependency. The assembly layer binds capabilities from
 * {@code ResolvedIdentity.effectiveCapabilities()} on each speak.
 * <p>
 * Fail-closed: a tool that is not in the bound capability set is
 * {@link ToolPermission#DENY}. A tool that is granted still follows the
 * original policy (AUTO / REQUIRES_APPROVAL / DENY). Unbound (no identity
 * yet) leaves the original policy unchanged so standalone use is intact.
 */
public class IdentityConstrainedPermissionChecker extends PermissionChecker {

    private volatile Set<String> allowedCapabilities;

    public IdentityConstrainedPermissionChecker(ToolPolicy policy) {
        super(policy);
    }

    /**
     * Bind the current identity's effective capabilities.
     * {@code null} clears the constraint (original policy only).
     */
    public void bindCapabilities(Collection<String> capabilities) {
        this.allowedCapabilities = capabilities == null ? null : Set.copyOf(capabilities);
    }

    @Override
    public ToolPermission check(String toolName) {
        Set<String> allowed = allowedCapabilities;
        if (allowed != null && !allowed.contains(toolName)) {
            return ToolPermission.DENY;
        }
        return super.check(toolName);
    }
}
