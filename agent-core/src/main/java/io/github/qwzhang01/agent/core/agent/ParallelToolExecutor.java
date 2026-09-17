package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ToolCall;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Parallel tool-call execution for the ReAct loop (Stage 9 Tool
 * Parallelism): fan out a response's plain tool calls, join them back in
 * the response's declaration order.
 * <p>
 * Semantics (the roadmap's four words):
 * <ul>
 *   <li><b>budget</b>: one shared step budget — parallel calls count as
 *       ONE step (they are one model response's work), and the executor
 *       rejects fan-out wider than {@code maxConcurrent} (default 8) by
 *       running the surplus sequentially in join order. The budget is
 *       never silently multiplied by parallelism.</li>
 *   <li><b>cancellation</b>: the ctx-aware run's cancellation/deadline is
 *       checked before dispatch and after the join. {@link #dispatchAll(List, Instant)}
 *       also applies the remaining deadline as a join timeout: unfinished
 *       calls become {@code [TIMEOUT]} strings and their futures are cancelled.
 *       Results are only written after the join, so a cancelled run leaves
 *       no orphan writes to state.</li>
 *   <li><b>ordering</b>: tool results are appended to the conversation
 *       history in the model's declaration order, whatever the finish
 *       order was — the provider invariant (every toolCall paired with a
 *       toolResult in order) survives parallelism.</li>
 *   <li><b>merge</b>: the join is all-of: the first failure fails the
 *       batch; successful siblings' results are still appended (partial
 *       results are better than dropped ones, and the model sees the
 *       failed call's error string next to them).</li>
 * </ul>
 * <p>
 * The executor is injected into {@link ReActAgentLoop} construction-time;
 * when null the loop keeps its sequential for-loop (bit-for-bit legacy
 * behavior). Handoff calls never enter this path — the loop routes them
 * out before dispatch.
 */
public final class ParallelToolExecutor {

    /** Default fan-out ceiling (surplus runs sequentially after the join). */
    public static final int DEFAULT_MAX_CONCURRENT = 8;

    /** One plain tool call to run, plus where to write its result. */
    public record Dispatch(ToolCall toolCall, ToolRun run) {
        public Dispatch {
            Objects.requireNonNull(toolCall, "toolCall");
            Objects.requireNonNull(run, "run");
        }
    }

    /** Runs one plain tool call; returns the result string. */
    @FunctionalInterface
    public interface ToolRun {
        String run(ToolCall toolCall) throws Exception;
    }

    private final Executor executor;
    private final int maxConcurrent;

    public ParallelToolExecutor(Executor executor) {
        this(executor, DEFAULT_MAX_CONCURRENT);
    }

    public ParallelToolExecutor(Executor executor, int maxConcurrent) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1, got " + maxConcurrent);
        }
        this.maxConcurrent = maxConcurrent;
    }

    /**
     * Fan out up to {@code maxConcurrent} dispatches in parallel, join in
     * declaration order. A failed call yields its error string as the
     * result (the loop's convention: tool failures are readable error
     * strings the model can self-correct on) unless the failure is an
     * interruption, which propagates.
     *
     * @return results in the same order as {@code dispatches}
     */
    public List<String> dispatchAll(List<Dispatch> dispatches) {
        return dispatchAll(dispatches, null);
    }

    /**
     * Same as {@link #dispatchAll(List)} but the join (and surplus sequential
     * calls) stop at {@code deadline}. Null deadline keeps the unbounded join.
     */
    public List<String> dispatchAll(List<Dispatch> dispatches, Instant deadline) {
        Objects.requireNonNull(dispatches, "dispatches");
        if (dispatches.isEmpty()) {
            return List.of();
        }
        if (deadlineExceeded(deadline)) {
            List<String> timedOut = new ArrayList<>(dispatches.size());
            for (Dispatch d : dispatches) {
                timedOut.add(timeoutResult(d));
            }
            return timedOut;
        }

        // Width clamp: dispatch the first maxConcurrent in parallel; the
        // surplus runs sequentially AFTER the join (budget discipline:
        // bounded fan-out, no unbounded thread fan).
        List<Dispatch> parallelSlice = dispatches.size() <= maxConcurrent
                ? dispatches
                : dispatches.subList(0, maxConcurrent);
        List<Dispatch> surplus = dispatches.size() <= maxConcurrent
                ? List.of()
                : dispatches.subList(maxConcurrent, dispatches.size());

        List<CompletableFuture<String>> futures = new ArrayList<>(parallelSlice.size());
        for (Dispatch d : parallelSlice) {
            futures.add(CompletableFuture.supplyAsync(() -> runQuietly(d), executor));
        }

        List<String> results = new ArrayList<>(dispatches.size());
        if (deadline == null) {
            for (CompletableFuture<String> f : futures) {
                results.add(join(f));
            }
        } else {
            results.addAll(joinUntil(parallelSlice, futures, deadline));
        }
        for (Dispatch d : surplus) {
            if (deadlineExceeded(deadline)) {
                results.add(timeoutResult(d));
            } else {
                results.add(runQuietly(d));
            }
        }
        return results;
    }

    private String runQuietly(Dispatch d) {
        try {
            return d.run().run(d.toolCall());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ie);
        } catch (Exception e) {
            // Tool failure convention: readable error string, model self-corrects.
            return "[ERROR] tool '" + d.toolCall().name() + "' failed: " + e.getMessage();
        }
    }

    private String join(CompletableFuture<String> f) {
        try {
            return f.join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CompletionException(interrupted);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private List<String> joinUntil(List<Dispatch> slice, List<CompletableFuture<String>> futures,
                                   Instant deadline) {
        long remainingMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            futures.forEach(f -> f.cancel(true));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            futures.forEach(f -> f.cancel(true));
            throw new CompletionException(interrupted);
        } catch (java.util.concurrent.ExecutionException ignored) {
            // Individual tool failures are already strings from runQuietly.
            // Interrupt-shaped exceptions are collected as TIMEOUT below.
        }
        List<String> results = new ArrayList<>(slice.size());
        for (int i = 0; i < slice.size(); i++) {
            results.add(resultOf(slice.get(i), futures.get(i)));
        }
        return results;
    }

    private String resultOf(Dispatch d, CompletableFuture<String> f) {
        if (!f.isDone() || f.isCancelled()) {
            f.cancel(true);
            return timeoutResult(d);
        }
        try {
            return f.join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            if (cause instanceof InterruptedException) {
                return timeoutResult(d);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private static boolean deadlineExceeded(Instant deadline) {
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    private static String timeoutResult(Dispatch d) {
        return "[TIMEOUT] tool '" + d.toolCall().name() + "' exceeded run deadline";
    }
}
