package io.github.qwzhang01.agent.enterprise.govern;

/**
 * Budget gate rejection (Stage 15 M15.3, D8 fail-closed).
 * <p>
 * Thrown by {@link CostLedger#requireBudget} when the tenant or user budget
 * is exhausted. Rejecting is the honest behavior: an SLA promises service
 * within the quota, not infinite service - silently burning tokens past the
 * quota is the failure mode this exception exists to prevent.
 */
public class BudgetExceededException extends RuntimeException {

    /**
     * Which budget dimension was exceeded.
     */
    public enum Dimension {
        TENANT,
        USER
    }

    private final Dimension dimension;
    private final long used;
    private final long limit;

    public BudgetExceededException(String message, Dimension dimension, long used, long limit) {
        super(message);
        this.dimension = dimension;
        this.used = used;
        this.limit = limit;
    }

    public Dimension dimension() {
        return dimension;
    }

    /** Tokens already consumed in the exceeded dimension. */
    public long used() {
        return used;
    }

    /** The limit that was crossed. */
    public long limit() {
        return limit;
    }
}
