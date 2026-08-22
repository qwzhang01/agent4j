package io.github.qwzhang01.agent.orchestrator;

import io.github.qwzhang01.agent.mcp.a2a.AgentCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The orchestrator: owns a pool of {@link AgentWorker}s and dispatches tasks to
 * them in parallel (Stage 11 M11.2).
 * <p>
 * "Supervisor" in name only for now: v1 dispatch is STATIC and EXPLICIT (D2) --
 * the caller decides which worker gets which task. LLM-driven dispatch (the
 * supervisor itself being an agent that reads AgentCards and allocates) is v2,
 * mirroring Stage 7's evolution from TaskScheduler to LlmDrivenScheduler.
 * <p>
 * Failure philosophy (D4): one crashed worker must not blow up the dispatch.
 * Everything a worker does wrong -- including pointing at an unregistered
 * worker -- comes back as {@code WorkerResult} failure DATA, never as an
 * exception out of {@link #dispatchAll}.
 * <p>
 * Lifecycle: the default constructor owns a cached thread pool (closed via
 * {@link #close()}); the executor-injecting constructor leaves the executor's
 * lifecycle to the caller.
 */
public class AgentSupervisor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentSupervisor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Order-preserving: skill routing needs deterministic "first match" (D7),
    // so registration order decides which worker wins a multi-match.
    private final Map<String, AgentWorker> workers =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    /**
     * Create a supervisor with its own cached thread pool.
     * Call {@link #close()} when done.
     */
    public AgentSupervisor() {
        this(Executors.newCachedThreadPool(), true);
    }

    /**
     * Create a supervisor on an injected executor; the executor's lifecycle
     * stays with the caller (not shut down by {@link #close()}).
     */
    public AgentSupervisor(ExecutorService executor) {
        this(executor, false);
    }

    private AgentSupervisor(ExecutorService executor, boolean ownsExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.ownsExecutor = ownsExecutor;
    }

    // ============ Worker pool ============

    /**
     * Register a worker. Duplicate names are rejected -- a worker's name is
     * its routing address.
     */
    public void register(AgentWorker worker) {
        Objects.requireNonNull(worker, "worker must not be null");
        synchronized (workers) {
            if (workers.containsKey(worker.name())) {
                throw new IllegalArgumentException("Worker already registered: " + worker.name());
            }
            workers.put(worker.name(), worker);
        }
        log.info("Registered worker '{}' (skills={})", worker.name(), worker.card().skills());
    }

    /**
     * Capability list of all registered workers, in registration order
     * (input for v2 LLM dispatch).
     */
    public List<AgentCard> discoverWorkers() {
        synchronized (workers) {
            return workers.values().stream().map(AgentWorker::card).toList();
        }
    }

    public AgentWorker getWorker(String name) {
        return workers.get(name);
    }

    public int workerCount() {
        return workers.size();
    }

    // ============ Dispatch ============

    /**
     * Dispatch with the default BEST_EFFORT policy, no retry, no timeout --
     * backward-compatible M11.2 behavior.
     */
    public SupervisorResult dispatchAll(List<WorkerTask> tasks, ResultAggregator aggregator) {
        return dispatchAll(tasks, aggregator, FailurePolicy.bestEffort());
    }

    /**
     * Dispatch all tasks to their workers IN PARALLEL, wait for every one of
     * them, aggregate (Stage 11 M11.3).
     * <p>
     * Per-task semantics (see {@link WorkerTask}):
     * <ul>
     *   <li>{@code timeoutMs > 0} -- a single attempt is cancelled and failed
     *       when it exceeds the budget; timeout counts as one failed attempt
     *       and can be retried.</li>
     *   <li>{@code maxRetries > 0} -- failed attempts are retried up to the
     *       budget, pausing {@code policy.retryBackoffMs()} between attempts.</li>
     * </ul>
     * Dispatch-wide semantics (see {@link FailurePolicy}):
     * <ul>
     *   <li>{@code FAIL_FAST} -- the first FINAL failure cancels all remaining
     *       tasks (they come back as "cancelled" failure data).</li>
     *   <li>{@code BEST_EFFORT} -- failures are isolated; the rest run on.</li>
     * </ul>
     * Never throws: everything a worker does wrong -- including pointing at an
     * unregistered worker -- comes back as {@code WorkerResult} failure data (D4).
     * <p>
     * Note: tasks WITH timeout/retry use nested submissions (attempt runs on
     * its own future so it can be timed out). With the default cached thread
     * pool this is safe; with an injected fixed pool of size N, keep
     * {@code tasks-with-timeout <= N} or you can starve the inner submissions.
     *
     * @param tasks      tasks in dispatch order (result order follows this)
     * @param aggregator how to merge the per-task results
     * @param policy     failure mode + retry pacing
     */
    public SupervisorResult dispatchAll(List<WorkerTask> tasks, ResultAggregator aggregator,
                                        FailurePolicy policy) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        Objects.requireNonNull(aggregator, "aggregator must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        if (tasks.isEmpty()) {
            return SupervisorResult.of(List.of(), aggregator.aggregate(List.of()), 0);
        }

        long start = System.currentTimeMillis();

        // 1. Submit everything in parallel. Unknown workers get no future
        //    (their failure is precomputed data, not an exception).
        List<Future<WorkerResult>> futures = new ArrayList<>(Collections.nCopies(tasks.size(), null));
        for (int i = 0; i < tasks.size(); i++) {
            WorkerTask task = tasks.get(i);
            AgentWorker worker = workers.get(task.workerName());
            if (worker != null) {
                futures.set(i, executor.submit(() -> executeWithPolicies(worker, task, policy)));
            }
        }

        // 2. Collect in task order -- the result list mirrors the task list.
        //    FAIL_FAST: the first final failure cancels everything still running.
        boolean failFast = policy.mode() == FailurePolicy.Mode.FAIL_FAST;
        String failFastTrigger = null;

        List<WorkerResult> results = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            WorkerTask task = tasks.get(i);

            if (failFastTrigger != null) {
                results.add(WorkerResult.failure(task,
                        "cancelled (FAIL_FAST: task '" + failFastTrigger + "' failed)", 0, 0));
                continue;
            }

            Future<WorkerResult> future = futures.get(i);
            WorkerResult result;
            if (future == null) {
                result = WorkerResult.failure(task,
                        "unknown worker '" + task.workerName() + "' (registered: "
                                + workers.keySet() + ")", 0, 0);
            } else {
                result = awaitQuietly(future, task);
            }
            results.add(result);

            if (failFast && !result.success()) {
                failFastTrigger = task.taskId();
                log.warn("FAIL_FAST: task '{}' failed, cancelling {} remaining task(s)",
                        task.taskId(), tasks.size() - i - 1);
                for (int j = i + 1; j < futures.size(); j++) {
                    Future<WorkerResult> f = futures.get(j);
                    if (f != null) {
                        f.cancel(true);
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        String aggregated = aggregator.aggregate(results);

        int succeeded = 0;
        for (WorkerResult r : results) {
            if (r.success()) succeeded++;
        }
        log.info("Dispatched {} task(s): {} succeeded, {} failed, {} ms",
                results.size(), succeeded, results.size() - succeeded, elapsed);

        return SupervisorResult.of(results, aggregated, elapsed);
    }

    // ============ Per-task execution: retry + timeout (M11.3) ============

    /**
     * Run one task honoring its retry budget and timeout. Fast path: a task
     * with no timeout and no retry executes the worker directly (no nested
     * submission). Timeout counts as one failed attempt and is retried like
     * any other failure.
     */
    private WorkerResult executeWithPolicies(AgentWorker worker, WorkerTask task,
                                             FailurePolicy policy) {
        long start = System.currentTimeMillis();
        int maxAttempts = 1 + task.maxRetries();
        WorkerResult last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean isolatable = task.timeoutMs() > 0;
            if (!isolatable) {
                // Fast path: run inline, no nested future needed.
                last = worker.execute(task);
            } else {
                // Timeout path: the attempt runs on its own future so we can
                // abandon (interrupt) it when the budget is spent.
                Future<WorkerResult> attemptFuture = executor.submit(() -> worker.execute(task));
                try {
                    last = attemptFuture.get(task.timeoutMs(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    attemptFuture.cancel(true);
                    last = WorkerResult.failure(task,
                            "timed out after " + task.timeoutMs() + " ms",
                            System.currentTimeMillis() - start, attempt);
                    log.warn("Task {} attempt {} timed out after {} ms",
                            task.taskId(), attempt, task.timeoutMs());
                } catch (ExecutionException e) {
                    // Defensive: the AgentWorker contract says "never throws".
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    last = WorkerResult.failure(task,
                            "unexpected throwable: " + cause,
                            System.currentTimeMillis() - start, attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    attemptFuture.cancel(true);
                    return withAttempts(WorkerResult.failure(task,
                            "interrupted", System.currentTimeMillis() - start, attempt), attempt);
                }
            }

            if (last.success()) {
                return withAttempts(last, attempt);
            }

            // Failed attempt -> backoff before the next one (if any).
            if (attempt < maxAttempts && policy.retryBackoffMs() > 0) {
                try {
                    Thread.sleep(policy.retryBackoffMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return withAttempts(last, attempt);
                }
            }
        }
        return withAttempts(last, maxAttempts);
    }

    /** Rebuild a result with the true attempt count (workers always report 1). */
    private static WorkerResult withAttempts(WorkerResult r, int attempts) {
        if (r.attempts() == attempts) {
            return r;
        }
        return new WorkerResult(r.taskId(), r.workerName(), r.success(), r.output(),
                r.error(), r.durationMs(), attempts, r.totalTokens());
    }

    /** future.get() that converts every failure mode into result data. */
    private static WorkerResult awaitQuietly(Future<WorkerResult> future, WorkerTask task) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Task {} threw unexpectedly: {}", task.taskId(), cause.toString());
            return WorkerResult.failure(task,
                    "unexpected throwable: " + cause, 0, 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WorkerResult.failure(task, "interrupted while waiting", 0, 1);
        }
    }

    // ============ Skill routing (M11.4, D7) ============

    /**
     * Find the first worker (registration order) whose {@link AgentCard}
     * declares the given skill.
     * <p>
     * The card is the worker's SELF-declaration -- fine as a ROUTING hint,
     * never as a trust basis (D5: trust comes from governance, not claims).
     */
    public Optional<AgentWorker> findWorkerBySkill(String skill) {
        Objects.requireNonNull(skill, "skill must not be null");
        synchronized (workers) {
            for (AgentWorker worker : workers.values()) {
                if (worker.card().skills().contains(skill)) {
                    return Optional.of(worker);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Dispatch a single task to the first worker whose card declares
     * {@code taskType} as a skill. Synchronous, no retry, no timeout --
     * for governed execution wrap it like any other dispatch.
     * <p>
     * Fail-closed (D7): no matching worker -> failure DATA listing the
     * available skills, never a silent no-op and never an exception.
     */
    public WorkerResult dispatchBySkill(String taskType, JsonNode payload) {
        Objects.requireNonNull(taskType, "taskType must not be null");
        Optional<AgentWorker> match = findWorkerBySkill(taskType);
        if (match.isEmpty()) {
            WorkerTask placeholder = WorkerTask.of("unrouted", taskType, payload);
            return WorkerResult.failure(placeholder,
                    "no worker with skill '" + taskType + "' (available skills: "
                            + availableSkills() + ")", 0, 0);
        }
        AgentWorker worker = match.get();
        WorkerTask task = WorkerTask.of(worker.name(), taskType, payload);
        return executeWithPolicies(worker, task, FailurePolicy.bestEffort());
    }

    /**
     * Prompt-style convenience: dispatch a prompt to the worker matching
     * the skill.
     */
    public WorkerResult dispatchBySkill(String taskType, String prompt) {
        return dispatchBySkill(taskType, MAPPER.createObjectNode().put("prompt", prompt));
    }

    private String availableSkills() {
        synchronized (workers) {
            return workers.values().stream()
                    .flatMap(w -> w.card().skills().stream())
                    .distinct()
                    .collect(Collectors.joining(", "));
        }
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
