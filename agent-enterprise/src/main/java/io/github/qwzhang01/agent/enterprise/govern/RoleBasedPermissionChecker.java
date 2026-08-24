package io.github.qwzhang01.agent.enterprise.govern;

import io.github.qwzhang01.agent.security.PermissionChecker;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Role-aware permission checker (Stage 15 M15.3) - the extension point
 * {@link PermissionChecker}'s javadoc has reserved since Stage 9:
 * "future stages can add context-aware logic (e.g. user role X can call
 * tool Y)". This milestone redeems that promise.
 * <p>
 * Composition rule (deny-first, fail-closed):
 * <ol>
 *   <li>the fallback {@link ToolPolicy} says DENY -> DENY. A hard deny can
 *       never be lifted by a role grant (the matrix restricts, it never
 *       overrides governance)</li>
 *   <li>any of the request's roles has the tool in its grant matrix -> AUTO</li>
 *   <li>otherwise the fallback policy decides (AUTO / REQUIRES_APPROVAL)</li>
 * </ol>
 * Net effect for a CSR: matrix tools are AUTO, unlisted sensitive tools
 * inherit REQUIRES_APPROVAL from the fallback, banned tools stay DENY.
 * <p>
 * Request scoping (blueprint D2): the role set is bound at construction -
 * one checker instance per request, wired into the
 * {@code GovernedToolExecutor} unchanged (it calls {@code check(toolName)}
 * with no user parameter; the binding happened earlier, at assembly time).
 */
public final class RoleBasedPermissionChecker extends PermissionChecker {

    private final Map<String, Set<String>> roleMatrix;
    private final Set<String> roles;

    private RoleBasedPermissionChecker(Map<String, Set<String>> roleMatrix,
                                       ToolPolicy fallbackPolicy,
                                       Set<String> roles) {
        super(fallbackPolicy);
        this.roleMatrix = Map.copyOf(roleMatrix);
        this.roles = Set.copyOf(roles);
    }

    /**
     * Create a request-scoped checker.
     *
     * @param roleMatrix      assembly-level static config: role name -> set of
     *                        tool names that role grants AUTO access to
     * @param fallbackPolicy  the Stage 9 policy deciding unlisted tools
     *                        (also the source of hard DENY entries)
     * @param roles           the requesting user's roles (from
     *                        {@code RequestContext.user().roles()}); empty
     *                        means everything falls back to the policy
     */
    public static RoleBasedPermissionChecker forRequest(
            Map<String, Set<String>> roleMatrix,
            ToolPolicy fallbackPolicy,
            Set<String> roles) {
        Objects.requireNonNull(roleMatrix, "roleMatrix must not be null");
        Objects.requireNonNull(fallbackPolicy, "fallbackPolicy must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        return new RoleBasedPermissionChecker(roleMatrix, fallbackPolicy, roles);
    }

    // ============ Decision ============

    @Override
    public ToolPermission check(String toolName) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        ToolPermission fallback = super.check(toolName);
        // 1. deny-first: hard denies cannot be lifted by role grants
        if (fallback == ToolPermission.DENY) {
            return ToolPermission.DENY;
        }
        // 2. role grant: any granted role makes the tool AUTO for this request
        for (String role : roles) {
            Set<String> granted = roleMatrix.get(role);
            if (granted != null && granted.contains(toolName)) {
                return ToolPermission.AUTO;
            }
        }
        // 3. fallback decides (AUTO / REQUIRES_APPROVAL)
        return fallback;
    }

    // ============ Accessors ============

    /**
     * The roles bound to this request-scoped checker (assembly/audit view).
     */
    public Set<String> boundRoles() {
        return roles;
    }

    /**
     * The static role matrix this checker consults (assembly/audit view).
     */
    public Map<String, Set<String>> roleMatrix() {
        return roleMatrix;
    }
}
