package io.github.qwzhang01.agent.enterprise.tenant;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of tenants and users, and the login gate of the enterprise profile
 * (Stage 15 M15.1).
 * <p>
 * v1 credential model is deliberately simple: one api key per user, held in
 * an internal credential table (never on the {@link User} record - identity
 * and credential are separate concerns, and {@code User.toString()} must not
 * leak secrets). Real SSO/OAuth is out of scope (blueprint §12); the mechanism
 * being validated here is "every request has a verified owner".
 * <p>
 * Fail-closed on every path (five rejection forms, each tested):
 * <ol>
 *   <li>unknown tenant</li>
 *   <li>unknown user</li>
 *   <li>api key mismatch</li>
 *   <li>tenant SUSPENDED (login and registration both reject)</li>
 *   <li>user's home tenant mismatch (registering a user under an unregistered
 *       tenant, or logging in with a tenantId that is not the user's home)</li>
 * </ol>
 */
public final class TenantRegistry {

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
    private final Map<String, User> users = new ConcurrentHashMap<>();
    /** userId -> api key. Never exposed through accessors. */
    private final Map<String, String> credentials = new ConcurrentHashMap<>();

    // ============ Registration ============

    /**
     * Register a tenant. Duplicate tenantId fails fast.
     */
    public void registerTenant(Tenant tenant) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Tenant existing = tenants.putIfAbsent(tenant.tenantId(), tenant);
        if (existing != null) {
            throw new EnterpriseAuthException(
                    "Tenant already registered: " + tenant.tenantId());
        }
    }

    /**
     * Register a user together with the api key used for login.
     * <p>
     * Fail-closed: the user's home tenant must already be registered and
     * ACTIVE (a user cannot belong to a nonexistent or suspended tenant),
     * and the userId must be globally unique.
     *
     * @param user   the user entity (home tenant membership is immutable)
     * @param apiKey login credential (blank rejected)
     */
    public void registerUser(User user, String apiKey) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        if (apiKey.isBlank()) {
            throw new EnterpriseAuthException("apiKey must not be blank for user: " + user.userId());
        }
        Tenant homeTenant = tenants.get(user.tenantId());
        if (homeTenant == null) {
            throw new EnterpriseAuthException(
                    "Cannot register user '" + user.userId() + "': home tenant '"
                            + user.tenantId() + "' is not registered");
        }
        if (!homeTenant.isActive()) {
            throw new EnterpriseAuthException(
                    "Cannot register user '" + user.userId() + "': home tenant '"
                            + user.tenantId() + "' is " + homeTenant.status());
        }
        User existing = users.putIfAbsent(user.userId(), user);
        if (existing != null) {
            throw new EnterpriseAuthException(
                    "User already registered: " + user.userId());
        }
        credentials.put(user.userId(), apiKey);
    }

    // ============ Login ============

    /**
     * Authenticate and produce the {@link RequestContext} for one request.
     * <p>
     * Fail-closed: unknown tenant, unknown user, key mismatch, suspended
     * tenant, or tenantId that is not the user's home tenant all throw
     * {@link EnterpriseAuthException}. There is no anonymous fallback.
     *
     * @param tenantId the tenant the caller claims to belong to
     * @param userId   the user claiming to log in
     * @param apiKey   the credential
     * @return a fully attributed request context (never null)
     */
    public RequestContext login(String tenantId, String userId, String apiKey) {
        Tenant tenant = tenants.get(tenantId);
        if (tenant == null) {
            throw new EnterpriseAuthException("Unknown tenant: " + tenantId);
        }
        if (!tenant.isActive()) {
            throw new EnterpriseAuthException(
                    "Tenant is " + tenant.status() + ": " + tenantId);
        }
        User user = users.get(userId);
        if (user == null) {
            throw new EnterpriseAuthException("Unknown user: " + userId);
        }
        if (!user.tenantId().equals(tenantId)) {
            throw new EnterpriseAuthException(
                    "User '" + userId + "' does not belong to tenant '" + tenantId
                            + "' (home tenant: " + user.tenantId() + ")");
        }
        String expected = credentials.get(userId);
        if (expected == null || !expected.equals(apiKey)) {
            throw new EnterpriseAuthException("Invalid api key for user: " + userId);
        }
        return new RequestContext(tenant, user, null);
    }

    // ============ Tenant Lifecycle ============

    /**
     * Suspend a registered tenant (admin action). Subsequent logins and user
     * registrations under this tenant fail closed; already-issued contexts are
     * unaffected (they hold their own immutable snapshot).
     *
     * @throws EnterpriseAuthException if the tenant is unknown
     */
    public void suspendTenant(String tenantId) {
        Tenant current = tenants.get(tenantId);
        if (current == null) {
            throw new EnterpriseAuthException("Unknown tenant: " + tenantId);
        }
        tenants.put(tenantId, current.suspended());
    }

    // ============ Lookups ============

    /**
     * Look up a registered tenant.
     */
    public Optional<Tenant> findTenant(String tenantId) {
        return Optional.ofNullable(tenants.get(tenantId));
    }

    /**
     * Look up a registered user (entity only - credentials are never exposed).
     */
    public Optional<User> findUser(String userId) {
        return Optional.ofNullable(users.get(userId));
    }
}
