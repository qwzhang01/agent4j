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
 * <p>
 * Stage 3.5 (harness roadmap): bounded capacity with explicit rejection.
 * A full queue refuses with {@link QueueFullException} — an overflow is a
 * loud, classified event (backpressure), never a silent OOM or an
 * unbounded latency cliff.
 */
public class AsyncTaskQueue {

    /** Backpressure signal: the queue is at capacity, producer must back off. */
    public static final class QueueFullException extends RuntimeException {
        public QueueFullException(int capacity, int size) {
            super("[QUEUE_FULL] Async task queue at capacity " + capacity
                    + " (current " + size + ") - reject and backpressure");
        }
    }

    private final int capacity;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicInteger totalEnqueued = new AtomicInteger();
    private final AtomicInteger totalConsumed = new AtomicInteger();
    private final AtomicInteger totalRejected = new AtomicInteger();
    private final PriorityBlockingQueue<RankedTask> queue = new PriorityBlockingQueue<>(11,
            Comparator.comparingInt((RankedTask r) -> r.task.priority().weight()).reversed()
                    .thenComparingLong(r -> r.seq));

    /** Unbounded (legacy default). */
    public AsyncTaskQueue() {
        this(Integer.MAX_VALUE);
    }

    /** Bounded: enqueue beyond capacity throws {@link QueueFullException}. */
    public AsyncTaskQueue(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Enqueue a task. Throws {@link QueueFullException} at capacity —
     * the caller maps it to its own failure semantics (drop, defer, fail).
     */
    public AsyncTask enqueue(AsyncTask task) {
        if (queue.size() >= capacity) {
            totalRejected.incrementAndGet();
            throw new QueueFullException(capacity, queue.size());
        }
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

    public int totalRejected() {
        return totalRejected.get();
    }

    public int capacity() {
        return capacity;
    }

    private record RankedTask(AsyncTask task, long seq) {
    }
}
