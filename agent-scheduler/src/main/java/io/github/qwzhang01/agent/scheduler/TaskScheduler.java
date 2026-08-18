package io.github.qwzhang01.agent.scheduler;

import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Stage 7 facade: scheduled resume, event-driven resume, async task queue.
 * <p>
 * Wraps {@link RunManager} (Stage 6) and adds automatic resume triggers.
 * Design decision (D1): TaskScheduler does NOT replace RunManager - it only
 * adds the "when to resume" layer. Execution is delegated to RunManager.
 * <p>
 * Usage:
 * <pre>{@code
 * TaskScheduler scheduler = new TaskScheduler(runManager);
 * scheduler.start();
 *
 * // Register a scheduled resume (2 hours)
 * scheduler.scheduleResume(runId, Duration.ofHours(2));
 *
 * // Register an event-driven resume
 * scheduler.waitForEvent(runId, "ci-passed:pr-123");
 *
 * // Fire an external event
 * scheduler.fireEvent("ci-passed:pr-123");
 *
 * scheduler.shutdown();
 * }</pre>
 */
public class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    private final RunManager runManager;
    private final ScheduledExecutorService executor;
    private final EventBroker eventBroker;
    private final AsyncTaskQueue taskQueue;
    private final Map<String, ScheduledResume> scheduledResumes = new ConcurrentHashMap<>();
    private final Map<String, TokenBudget> runBudgets = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    // ============ Constructors ============

    public TaskScheduler(RunManager runManager) {
        this(runManager, Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "agent-scheduler");
            t.setDaemon(true);
            return t;
        }));
    }

    public TaskScheduler(RunManager runManager, ScheduledExecutorService executor) {
        this.runManager = runManager;
        this.executor = executor;
        this.eventBroker = new EventBroker(runManager);
        this.taskQueue = new AsyncTaskQueue();
    }

    // ============ Lifecycle ============

    public TaskScheduler start() {
        running = true;
        log.info("[scheduler] Started");
        return this;
    }

    public void shutdown() {
        running = false;
        executor.shutdown();
        log.info("[scheduler] Shut down");
    }

    // ============ Scheduled Resume ============

    /**
     * Schedule a one-time resume after a delay.
     */
    public ScheduledResume scheduleResume(String runId, Duration delay) {
        return scheduleResume(runId, Instant.now().plus(delay), false, null);
    }

    /**
     * Schedule a one-time resume at a specific time.
     */
    public ScheduledResume scheduleResume(String runId, Instant fireAt) {
        return scheduleResume(runId, fireAt, false, null);
    }

    /**
     * Schedule a recurring resume (e.g. check every 2 hours).
     */
    public ScheduledResume scheduleRecurringResume(String runId, Duration interval) {
        return scheduleResume(runId, Instant.now().plus(interval), true, interval);
    }

    private ScheduledResume scheduleResume(String runId, Instant fireAt, boolean recurring, Duration interval) {
        ScheduledResume sr = recurring
                ? ScheduledResume.recurring(runId, fireAt, interval)
                : ScheduledResume.once(runId, fireAt);
        scheduledResumes.put(sr.resumeId(), sr);

        long delayMs = Math.max(0, Duration.between(Instant.now(), fireAt).toMillis());
        if (recurring && interval != null) {
            executor.scheduleAtFixedRate(
                    () -> doResume(runId, sr.resumeId()),
                    delayMs, interval.toMillis(), TimeUnit.MILLISECONDS);
            log.info("[scheduler] Scheduled recurring resume for run '{}', interval={}ms", runId, interval.toMillis());
        } else {
            executor.schedule(
                    () -> doResume(runId, sr.resumeId()),
                    delayMs, TimeUnit.MILLISECONDS);
            log.info("[scheduler] Scheduled resume for run '{}' in {}ms", runId, delayMs);
        }
        return sr;
    }

    private void doResume(String runId, String resumeId) {
        if (!running) return;
        try {
            log.info("[scheduler] Auto-resuming run '{}'", runId);
            runManager.resume(runId);
            if (!scheduledResumes.get(resumeId).recurring()) {
                scheduledResumes.remove(resumeId);
            }
        } catch (Exception e) {
            log.error("[scheduler] Failed to auto-resume run '{}': {}", runId, e.getMessage());
        }
    }

    // ============ Event-Driven Resume ============

    /**
     * Register a run to wait for an event. When {@link #fireEvent(String)}
     * is called, the run is automatically resumed.
     */
    public EventTrigger waitForEvent(String runId, String eventKey) {
        return waitForEvent(runId, eventKey, null);
    }

    /**
     * Register a run to wait for an event, with a timeout.
     * If the event doesn't fire within the timeout, the run fails.
     */
    public EventTrigger waitForEvent(String runId, String eventKey, Duration timeout) {
        EventTrigger trigger = EventTrigger.of(runId, eventKey, timeout);
        eventBroker.subscribe(trigger);

        if (timeout != null) {
            executor.schedule(() -> {
                if (!trigger.isFired()) {
                    log.warn("[scheduler] Event '{}' timed out for run '{}'", eventKey, runId);
                    // The run will be resumed; the node should check for timeout
                    try {
                        runManager.resume(runId);
                    } catch (Exception e) {
                        log.error("[scheduler] Failed to resume after timeout: {}", e.getMessage());
                    }
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        return trigger;
    }

    /**
     * Fire an external event. All runs subscribed to this event are resumed.
     */
    public void fireEvent(String eventKey) {
        eventBroker.fire(eventKey);
    }

    /**
     * Fire an external event with a payload (available to resumed nodes).
     */
    public void fireEvent(String eventKey, Object payload) {
        eventBroker.fire(eventKey, payload);
    }

    /** Check if an event has fired (for nodes to check on resume). */
    public boolean hasEventFired(String eventKey) {
        return eventBroker.hasFired(eventKey);
    }

    /** Get the payload of a fired event. */
    public Object getEventPayload(String eventKey) {
        return eventBroker.getPayload(eventKey);
    }

    // ============ Async Task Queue ============

    /** Enqueue an async task produced by an Agent. */
    public AsyncTask enqueueTask(AsyncTask task) {
        return taskQueue.enqueue(task);
    }

    /** Poll the next task by priority (URGENT first, then FIFO). */
    public AsyncTask pollNextTask() {
        return taskQueue.pollNext();
    }

    /** List all pending tasks (for inspection). */
    public List<AsyncTask> pendingTasks() {
        return taskQueue.peekAll();
    }

    // ============ Token Budget ============

    /** Set a token budget for a run. */
    public TokenBudget setBudget(String runId, long tokenLimit) {
        TokenBudget budget = new TokenBudget(tokenLimit);
        runBudgets.put(runId, budget);
        return budget;
    }

    /** Consume tokens for a run. Returns false if budget exceeded. */
    public boolean consumeTokens(String runId, long tokens) {
        TokenBudget budget = runBudgets.get(runId);
        if (budget == null) {
            return true;  // no budget set = unlimited
        }
        return budget.consume(tokens);
    }

    public TokenBudget getBudget(String runId) {
        return runBudgets.get(runId);
    }

    // ============ Inspection ============

    public Map<String, ScheduledResume> getScheduledResumes() {
        return Map.copyOf(scheduledResumes);
    }

    public EventBroker getEventBroker() {
        return eventBroker;
    }

    public AsyncTaskQueue getTaskQueue() {
        return taskQueue;
    }

    public RunManager getRunManager() {
        return runManager;
    }
}
