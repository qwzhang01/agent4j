package io.github.qwzhang01.agent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The periodic caller {@link JdbcTaskQueue#requeueOrphaned(long)} was
 * missing "requeueOrphaned has no periodic caller" — a
 * crashed worker's RUNNING rows only ever got requeued if a host
 * remembered to sweep by hand. This sweeper calls the sweep on a fixed
 * period, closing the crash-recovery loop: a dead holder's tasks return
 * to PENDING and another worker claims them. Harness batch 5
 * (2026-09-17).
 * <p>
 * Failure semantics: a store error during a sweep does not stop the
 * sweeper — a dead sweeper would silently disable crash recovery, worse
 * than the gap this class closes; the error is counted, logged, and the
 * next period retries. The sweep itself is CAS-guarded per row, so
 * multiple sweepers (or a manual sweep racing a scheduled one) never
 * double-requeue.
 */
public final class TaskLeaseSweeper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TaskLeaseSweeper.class);

    private final JdbcTaskQueue queue;
    private final long graceMillis;
    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> future;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sweeps = new AtomicLong();
    private final AtomicLong requeuedTotal = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    /**
     * Sweep every {@code sweepIntervalMillis}; the first sweep fires
     * immediately (crash leftovers are cleaned at boot). RUNNING rows
     * whose lease ({@code COALESCE(heartbeat_at, started_at)}) is older
     * than {@code graceMillis} are requeued to PENDING.
     */
    public TaskLeaseSweeper(JdbcTaskQueue queue, long sweepIntervalMillis, long graceMillis) {
        this.queue = Objects.requireNonNull(queue);
        this.graceMillis = graceMillis;
        if (sweepIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "sweep interval must be positive: " + sweepIntervalMillis);
        }
        if (graceMillis < 0) {
            throw new IllegalArgumentException("grace must not be negative: " + graceMillis);
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent4j-task-lease-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.future = executor.scheduleWithFixedDelay(this::sweep, 0, sweepIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /** Sweeps executed, successful or not. */
    public long totalSweeps() {
        return sweeps.get();
    }

    /** Rows actually requeued across all sweeps (CAS winners only). */
    public long totalRequeued() {
        return requeuedTotal.get();
    }

    /** Failed sweeps (store errors), for monitoring. */
    public long totalErrors() {
        return errors.get();
    }

    private void sweep() {
        if (closed.get()) {
            return;
        }
        try {
            sweeps.incrementAndGet();
            List<String> requeued = queue.requeueOrphaned(graceMillis);
            requeuedTotal.addAndGet(requeued.size());
            if (!requeued.isEmpty()) {
                log.info("task lease sweep requeued {} orphaned task(s) past the {} ms "
                        + "grace window: {}", requeued.size(), graceMillis, requeued);
            }
        } catch (RuntimeException e) {
            if (closed.get()) {
                return; // shutdown race, not a sweep failure
            }
            errors.incrementAndGet();
            log.warn("task lease sweep failed: crash recovery degraded, "
                    + "next sweep retries", e);
        }
    }

    /**
     * Stop sweeping. Orphaned rows then wait for the next sweeper or a
     * manual {@link JdbcTaskQueue#requeueOrphaned(long)} call.
     * Idempotent.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            future.cancel(false);
            executor.shutdownNow();
        }
    }
}
