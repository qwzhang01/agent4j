package io.github.qwzhang01.agent.security;

/**
 * Checks tool permissions against a {@link ToolPolicy} (Stage 9).
 * <p>
 * Thin wrapper around ToolPolicy - separated into its own class so that
 * future stages can add context-aware logic (e.g. "user role X can call
 * tool Y") without changing the policy data structure.
 */
public class PermissionChecker {

    private final ToolPolicy policy;

    public PermissionChecker(ToolPolicy policy) {
        this.policy = policy;
    }

    /**
     * Check what permission level applies to this tool call.
     *
     * @param toolName the tool the model wants to call
     * @return the permission decision (AUTO / REQUIRES_APPROVAL / DENY)
     */
    public ToolPermission check(String toolName) {
        return policy.permissionFor(toolName);
    }

    /**
     * Whether the tool call is outright denied (no execution, no approval).
     */
    public boolean isDenied(String toolName) {
        return check(toolName) == ToolPermission.DENY;
    }

    /**
     * Whether the tool call needs human approval before execution.
     */
    public boolean requiresApproval(String toolName) {
        return check(toolName) == ToolPermission.REQUIRES_APPROVAL;
    }

    /**
     * Whether the tool call can execute automatically.
     */
    public boolean isAuto(String toolName) {
        return check(toolName) == ToolPermission.AUTO;
    }
}
