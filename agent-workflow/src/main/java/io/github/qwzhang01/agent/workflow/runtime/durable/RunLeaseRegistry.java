package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Execution lease over a run (, harness roadmap).
 * <p>
 * "Two workers simultaneously recovering the same Run: only one gets the
 * Lease." Acquire is compare-and-set on the run's row: the first worker to
 * transition WAITING→RUNNING holds the lease until it releases, its lease
 * expires, or the run reaches a terminal state. A second worker's acquire
 * fails loudly, not silently.
 * <p>
 * This in-memory implementation is the reference semantics; the production
 * lease (DB row lock or Redis SETNX with TTL) must reproduce exactly these
 * three rules.
 */
public final class RunLeaseRegistry implements RunLeases {

    private record Lease(String holder, long acquiredAt, long expiresAt) {
        boolean isExpired(long now) {
            return expiresAt > 0 && now >= expiresAt;
        }
    }

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();

    /**
     * Try to acquire the lease for a run.
     *
     * @param holder worker identity (host:thread, instance id)
     * @param ttlMillis lease lifetime; {@code <=0} = no expiry (held until release)
     * @return true when this worker now holds the lease
     */
    @Override
    public boolean tryAcquire(String runId, String holder, long ttlMillis) {
        long now = System.currentTimeMillis();
        Lease fresh = new Lease(holder, now, ttlMillis <= 0 ? 0 : now + ttlMillis);
        Lease winner = leases.compute(runId, (id, current) ->
                current == null || current.isExpired(now) ? fresh : current);
        return winner.holder().equals(holder);
    }

    /**
     * heartbeat: extend a lease this holder still owns. Returns
     * false when ownership was lost (expired + taken over, or another
     * holder) — the caller must stop touching the run at once.
     */
    @Override
    public boolean renew(String runId, String holder, long ttlMillis) {
        long now = System.currentTimeMillis();
        Lease current = leases.get(runId);
        if (current == null || !current.holder().equals(holder) || current.isExpired(now)) {
            return false;
        }
        Lease extended = new Lease(holder, current.acquiredAt(),
                ttlMillis <= 0 ? 0 : now + ttlMillis);
        return leases.replace(runId, current, extended);
    }

    /** Release the lease; only the holder may release. */
    @Override
    public boolean release(String runId, String holder) {
        Lease current = leases.get(runId);
        if (current == null || !current.holder().equals(holder)) {
            return false;
        }
        leases.remove(runId, current);
        return true;
    }

    /** Whether the run's lease is currently held (and not expired). */
    @Override
    public boolean isHeld(String runId) {
        Lease current = leases.get(runId);
        return current != null && !current.isExpired(System.currentTimeMillis());
    }

    /** Who holds the lease (empty when free or expired). */
    @Override
    public Optional<String> holder(String runId) {
        Lease current = leases.get(runId);
        if (current == null || current.isExpired(System.currentTimeMillis())) {
            return Optional.empty();
        }
        return Optional.of(current.holder());
    }
}
