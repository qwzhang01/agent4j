package io.github.qwzhang01.agent.enterprise.tenant;

import java.util.Objects;
import java.util.Set;

/**
 * A user entity - the human behind a request (Stage 15 M15.1).
 * <p>
 * The Runtime (Stage 1-14) assumed the caller is a faceless program; the
 * enterprise profile replaces that with a concrete employee: roles decide the
 * tool permission matrix (M15.3), tenant membership decides the isolation
 * boundary, and the userId becomes the audit attribution
 * ("who asked the Agent to do this").
 * <p>
 * Credentials are deliberately NOT part of this record: the api key lives in
 * the {@link TenantRegistry}'s credential table only. Identity and credential
 * are separate concerns - {@code toString()} of this record must never leak
 * login secrets.
 *
 * @param userId      unique user identifier (global, e.g. "u-alice")
 * @param tenantId    the tenant this user belongs to (immutable membership, v1)
 * @param displayName human-readable name
 * @param roles       role names feeding the permission matrix
 *                    (e.g. ["agent:csr", "supervisor"]); empty = no grants
 */
public record User(
        String userId,
        String tenantId,
        String displayName,
        Set<String> roles
) {

    /** Common role name for front-line customer service representatives. */
    public static final String ROLE_CSR = "agent:csr";
    /** Common role name for supervisors who can approve sensitive operations. */
    public static final String ROLE_SUPERVISOR = "supervisor";

    public User {
        requireText(userId, "userId");
        requireText(tenantId, "tenantId");
        requireText(displayName, "displayName");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    // ============ Accessors ============

    /**
     * Whether this user holds the given role.
     */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    // ============ Helpers ============

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
