package io.github.qwzhang01.agent.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async task queue: holds tasks produced by Agents at runtime.
 * <p>
 * Design decision (D4): this is dynamic dispatch, not ParallelNode's static
 * parallelism. Agents produce tasks during execution; consumers poll and
 * execute them.
 * <p>
 * Ordering: by priority descending (URGENT first), then by enqueue sequence
 * (FIFO within same priority). {@link #pollNext()} is thread-safe.
 */
public class AsyncTaskQueue {

    private final AtomicLong seq = new AtomicLong();
    private final AtomicInteger totalEnqueued = new AtomicInteger();
    private final AtomicInteger totalConsumed = new AtomicInteger();
    private final PriorityBlockingQueue<RankedTask> queue = new PriorityBlockingQueue<>(11,
            Comparator.comparingInt((RankedTask r) -> r.task.priority().weight()).reversed()
                    .thenComparingLong(r -> r.seq));

    /** Enqueue a task. */
    public AsyncTask enqueue(AsyncTask task) {
        queue.offer(new RankedTask(task, seq.getAndIncrement()));
        totalEnqueued.incrementAndGet();
        return task;
    }

    /**
     * Poll the next task by priority (URGENT > HIGH > NORMAL > LOW),
     * then by enqueue order (FIFO). Thread-safe; each task is returned once.
     */
    public AsyncTask pollNext() {
        RankedTask ranked = queue.poll();
        if (ranked == null) {
            return null;
        }
        totalConsumed.incrementAndGet();
        return ranked.task;
    }

    /** Peek without removing (for inspection). */
    public List<AsyncTask> peekAll() {
        return queue.stream()
                .sorted(queue.comparator())
                .map(RankedTask::task)
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

    private record RankedTask(AsyncTask task, long seq) {
    }
}
