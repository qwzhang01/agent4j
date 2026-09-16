package io.github.qwzhang01.agent.spring;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Graceful shutdown coordinator (Stage 8.2): "提供优雅停机：停止接收新
 * Run，等待或持久化已有 Run".
 * <p>
 * Phase 1 — gate: {@link #beginShutdown()} flips a volatile bit; every
 * {@code start()} call after that is rejected with a clear exception
 * (new runs must not sneak in behind the drain).
 * <p>
 * Phase 2 — drain: registered in-flight run tasks are awaited, bounded
 * by a timeout. Tasks that finish leave the list. When the timeout
 * expires, remaining tasks are cancelled: better a persisted PAUSED run
 * than a killed JVM with a half-finished side effect and no checkpoint.
 * <p>
 * The coordinator deliberately does NOT own the run lifecycle: it hands
 * the app a {@link RunHandle} per in-flight run and lets the app decide
 * what "wait or persist" means for its runtime (in-memory RunManager vs
 * DurableRunManager with checkpoint-on-pause). What it guarantees is the
 * ordering discipline: gate closed → drain bounded → stragglers
 * cancelled → report.
 */
public class GracefulShutdownCoordinator {

    /** Thrown when a run is started after {@link #beginShutdown()}. */
    public static class ShutdownInProgressException extends RuntimeException {
        public ShutdownInProgressException(String message) {
            super(message);
        }
    }

    /** One in-flight run tracked during drain. */
    public interface RunHandle {

        /** The run's id (for the report). */
        String runId();

        /**
         * Wait for this run to reach a terminal/persisted state, up to
         * {@code timeoutMs}. Return true if it drained in time.
         */
        boolean awaitDrain(long timeoutMs) throws InterruptedException;

        /** Force-cancel at the next opportunity (node boundary). */
        void cancel();
    }

    private volatile boolean shuttingDown = false;
    private final List<RunHandle> inFlight = new CopyOnWriteArrayList<>();

    /** Flip the gate: new runs are rejected from this call on. */
    public void beginShutdown() {
        shuttingDown = true;
    }

    /** True once {@link #beginShutdown()} has been called. */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /** Guard call at run-start time; throws when the gate is closed. */
    public void checkStartAllowed() {
        if (shuttingDown) {
            throw new ShutdownInProgressException(
                    "Shutdown in progress - refusing new run start");
        }
    }

    /** Track an in-flight run for the drain phase. */
    public void track(RunHandle handle) {
        // Runs that slipped in just before the flip are still tracked and
        // drain like any other — never silently dropped from the report.
        inFlight.add(handle);
    }

    /**
     * Drain: wait up to {@code timeoutMs} total for in-flight runs, then
     * cancel the stragglers. Returns a report of what happened per run.
     */
    public ShutdownReport drain(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<RunHandle> remaining = new java.util.ArrayList<>(inFlight);
        for (RunHandle handle : remaining) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0 || !handle.awaitDrain(left)) {
                handle.cancel();
            }
        }
        return new ShutdownReport(
                List.copyOf(inFlight.stream().map(RunHandle::runId).toList()),
                inFlight.size());
    }

    /** Result of a drain: which runs were drained/cancelled. */
    public record ShutdownReport(List<String> runIds, int total) {
        @Override
        public String toString() {
            return "ShutdownReport{drained=" + total + ", runIds=" + runIds + '}';
        }
    }
}
