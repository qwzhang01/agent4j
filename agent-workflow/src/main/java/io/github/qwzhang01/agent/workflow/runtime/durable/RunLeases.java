package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.util.Optional;

/**
 * Execution-lease contract (, harness roadmap).
 * <p>
 * "Two workers recovering the same Run: only one holds the Lease." The
 * reference semantics live in {@link RunLeaseRegistry} (in-memory CAS);
 * production backends ({@code JdbcRunLeases}, Redis SETNX, DB row locks)
 * must reproduce exactly these rules:
 * <ol>
 *   <li><b>Single winner</b> — acquire is compare-and-set; the second
 *       worker fails loudly, never silently.</li>
 *   <li><b>TTL</b> — a crashed holder's lease frees after the window, so
 *       recovery can take over.</li>
 *   <li><b>Heartbeat</b> — a <em>live</em> holder renews (fix: a
 *       resume legitimately longer than the TTL was previously take-overable
 *       mid-flight, a duplicate-execution bug). Renew only succeeds for the
 *       current holder of a non-expired lease.</li>
 *   <li><b>Holder-only release</b> — a worker may never release a lease it
 *       does not hold.</li>
 * </ol>
 */
public interface RunLeases {

    /**
     * Try to acquire the lease for a run.
     *
     * @param holder worker identity (host:thread, instance id)
     * @param ttlMillis lease lifetime; {@code <=0} = no expiry (held until release)
     * @return true when this worker now holds the lease
     */
    boolean tryAcquire(String runId, String holder, long ttlMillis);

    /**
     * Heartbeat: extend the lease for the current holder. Fails (returns
     * false) when the caller is not the holder, or the lease already
     * expired and was taken over — in that case the worker must stop
     * touching the run immediately (it lost ownership).
     *
     * @param ttlMillis new lifetime from now; {@code <=0} = no expiry
     * @return true when the lease is still this holder's and was extended
     */
    boolean renew(String runId, String holder, long ttlMillis);

    /** Release the lease; only the holder may release. */
    boolean release(String runId, String holder);

    /** Whether the run's lease is currently held (and not expired). */
    boolean isHeld(String runId);

    /** Who holds the lease (empty when free or expired). */
    Optional<String> holder(String runId);
}
