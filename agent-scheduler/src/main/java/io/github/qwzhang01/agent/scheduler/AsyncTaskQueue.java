package io.github.qwzhang01.agent.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async task queue: holds tasks produced by Agents at runtime.
 * <p>
 * Design decision (D4): this is dynamic dispatch, not ParallelNode's static
 * parallelism. Agents produce tasks during execution; consumers poll and
 * execute them.
 * <p>
 * Ordering: by priority descending (URGENT first), then by creation time
 * (FIFO within same priority).
 */
public class AsyncTaskQueue {

    private final ConcurrentLinkedQueue<AsyncTask> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger totalEnqueued = new AtomicInteger();
    private final AtomicInteger totalConsumed = new AtomicInteger();

    /** Enqueue a task. */
    public AsyncTask enqueue(AsyncTask task) {
        queue.add(task);
        totalEnqueued.incrementAndGet();
        return task;
    }

    /**
     * Poll the next task by priority (URGENT > HIGH > NORMAL > LOW),
     * then by creation time (FIFO).
     */
    public AsyncTask pollNext() {
        AsyncTask next = queue.stream()
                .sorted(Comparator
                        .comparing((AsyncTask t) -> t.priority().weight()).reversed()
                        .thenComparing(AsyncTask::createdAt))
                .findFirst()
                .orElse(null);
        if (next != null && queue.remove(next)) {
            totalConsumed.incrementAndGet();
            return next;
        }
        return null;
    }

    /** Peek without removing (for inspection). */
    public List<AsyncTask> peekAll() {
        return queue.stream()
                .sorted(Comparator
                        .comparing((AsyncTask t) -> t.priority().weight()).reversed()
                        .thenComparing(AsyncTask::createdAt))
                .toList();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int totalEnqueued() {
        return totalEnqueued.get();
    }

    public int totalConsumed() {
        return totalConsumed.get();
    }
}
