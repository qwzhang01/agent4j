package io.github.qwzhang01.agent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holder-side lease keeper for one claimed {@link JdbcTaskQueue} task:
 * renews {@link JdbcTaskQueue#heartbeat(String)} on a fixed period until
 * {@link #close}, so a task that runs longer than the sweep grace
 * window is never mistaken for an orphan. Harness batch 5 (2026-09-17).
 * <p>
 * Failure semantics follow the module's heartbeat precedent — fail-open
 * on store hiccups, fail-closed on ownership: a database error during a
 * renewal does NOT mark the lease lost (no sweep can run against the
 * same dead store either, so the lease cannot silently slip away during
 * a partition); the error is counted and renewal keeps trying. A renewal
 * that returns {@code false} — the row is no longer RUNNING (completed,
 * cancelled, or requeued by a sweep) — DOES mark the lease lost and
 * stops renewing; a worker observing {@link #lost} between work units
 * should abandon the task rather than keep spending on it.
 * <p>
 * Two thread shapes (harness batch 7): the legacy constructors own ONE
 * DAEMON THREAD PER HOLDER (fine for a handful of claimed tasks); hosts
 * claiming many tasks concurrently should hand ONE SHARED
 * {@link ScheduledExecutorService} to the new constructor — every
 * holder then rides the pool's threads and {@link #close} only cancels
 * its own future, never shuts the shared pool down. The primitive
 * {@link JdbcTaskQueue#heartbeat(String)} remains the API; this class is
 * the convenience.
 */
public final class ClaimedTaskHeartbeat implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaimedTaskHeartbeat.class);

    private final JdbcTaskQueue queue;
    private final String taskId;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;
    private final ScheduledFuture<?> future;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong errors = new AtomicLong();

    /**
     * Renew {@code taskId}'s lease every {@code intervalMillis} on a
     * dedicated daemon thread. The first renewal fires after one interval
     * (a fresh claim already stamped the lease clock at claim time).
     */
    public ClaimedTaskHeartbeat(JdbcTaskQueue queue, String taskId, long intervalMillis) {
        this(queue, taskId, intervalMillis, null);
    }

    /**
     * Shared-pool shape (harness batch 7): renew on the GIVEN executor —
     * the holder never spawns a thread of its own and {@link #close}
     * never shuts the pool down. The pool must outlive every holder on it
     * (the host owns its lifecycle). A null executor falls back to the
     * dedicated-thread shape.
     *
     * @param executor the host-owned shared pool; must not be shut down
     *                 while any holder is alive
     */
    public ClaimedTaskHeartbeat(JdbcTaskQueue queue, String taskId, long intervalMillis,
                                ScheduledExecutorService executor) {
        this.queue = Objects.requireNonNull(queue);
        this.taskId = Objects.requireNonNull(taskId);
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "heartbeat interval must be positive: " + intervalMillis);
        }
        if (executor != null) {
            this.executor = executor;
            this.ownsExecutor = false;
        } else {
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent4j-task-heartbeat-" + taskId);
                t.setDaemon(true);
                return t;
            });
            this.ownsExecutor = true;
        }
        this.future = this.executor.scheduleWithFixedDelay(this::tick, intervalMillis,
                intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** The task whose lease this holder keeps. */
    public String taskId() {
        return taskId;
    }

    /**
     * True once a renewal found the row no longer RUNNING (completed,
     * cancelled, or requeued by a sweep): the holder should stop work.
     * Store errors never set this — only an ownership verdict does.
     */
    public boolean lost() {
        return lost.get();
    }

    /** Failed renewal attempts (store errors), for monitoring. */
    public long errors() {
        return errors.get();
    }

    private void tick() {
        if (closed.get()) {
            return;
        }
        try {
            if (!queue.heartbeat(taskId)) {
                lost.set(true);
                future.cancel(false);
                log.warn("task {} lease lost: row no longer RUNNING (completed, cancelled "
                        + "or requeued by a sweep) - stopping renewal", taskId);
            }
        } catch (RuntimeException e) {
            if (closed.get()) {
                return; // shutdown race, not a renewal failure
            }
            errors.incrementAndGet();
            log.warn("task {} heartbeat attempt failed: keeping the lease open, "
                    + "retrying next interval", taskId, e);
        }
    }

    /**
     * Stop renewing. The row's lease then ages normally into sweep
     * range (crash cleanup, or a deliberate hand-back). Idempotent.
     * A shared pool is never shut down here — only the holder's own
     * future is cancelled; the host owns the pool's lifecycle.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            future.cancel(false);
            if (ownsExecutor) {
                executor.shutdownNow();
            }
        }
    }
}
