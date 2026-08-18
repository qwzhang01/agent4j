package io.github.qwzhang01.agent.scheduler;

/**
 * Priority levels for async tasks in the queue.
 * Higher priority tasks are consumed first.
 */
public enum TaskPriority {
    LOW(1),
    NORMAL(5),
    HIGH(8),
    URGENT(10);

    private final int weight;

    TaskPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
