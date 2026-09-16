package io.github.qwzhang01.agent.core.run;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified cooperative cancellation (Stage 1.4 of the harness roadmap).
 * <p>
 * One source per run. The source is the only mutable corner of the
 * Stage-1 context machinery: everything else in {@link RunContext} is
 * immutable. Callers hold the read-only {@link CancellationToken} view
 * via {@link #token()}; only the owner (the entry adapter or
 * {@code RunManager.cancel}) may call {@link #cancel()}.
 * <p>
 * Cancellation is cooperative, never preemptive: long-running components
 * (model call wrapper, tool loop, workflow runtime, sandbox) check
 * {@code token.isCancelled()} at their natural boundaries and translate
 * a hit into {@link RunCancelledException} — a structured signal, not a
 * business failure. Cancellation must never surface as {@code ERROR}
 * with a free-text message (roadmap 1.4: "cancellation must not be
 * recorded as a business failure").
 * <p>
 * v1 scope: a plain boolean flag, no listener callbacks. This keeps the
 * primitive allocation-free on the hot path (every step boundary reads
 * it). Registering cancellation listeners is deferred until a consumer
 * actually needs them.
 */
public final class CancellationSource {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CancellationToken token = new Token();

    /**
     * Request cancellation. Idempotent; returns true only for the first
     * caller (the one whose request actually flipped the flag).
     */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /** Whether cancellation was requested. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /** The read-only view carried inside a {@link RunContext}. */
    public CancellationToken token() {
        return token;
    }

    private final class Token implements CancellationToken {
        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void check() {
            if (cancelled.get()) {
                throw new RunCancelledException("Run cancelled before this step");
            }
        }

        @Override
        public String toString() {
            return "CancellationToken(cancelled=" + cancelled.get() + ")";
        }
    }
}
