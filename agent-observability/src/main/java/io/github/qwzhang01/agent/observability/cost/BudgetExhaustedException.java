package io.github.qwzhang01.agent.observability.cost;

/**
 * The budget is gone - fail-closed (Stage 18 D3, the honest refusal).
 * <p>
 * Thrown by {@link io.github.qwzhang01.agent.observability.routing.BudgetAwareRouter}
 * when the remaining budget is exactly zero (routing to a cheaper model cannot
 * help - ANY call would overdraft), and available to assembly-layer gates that
 * turn a {@link BudgetCheck.Denied} from {@link BudgetBook#requireBudget} into
 * an exception (blueprint T4).
 * <p>
 * Stage 15 sibling: {@code agent-enterprise BudgetExceededException} carries
 * the same semantics (Dimension/used/limit). This type exists because D4
 * forbids the dependency direction - observability is the layer UNDER the
 * enterprise domain and cannot import it. The distinct name avoids ambiguous
 * imports where an assembly bridges both ledgers.
 * <p>
 * Deliberately NOT a {@code ModelException}: budget exhaustion is not a
 * transient model failure. A {@code FallbackModelClient} wrapping the thrower
 * must not "recover" from it by retrying another provider - retrying does not
 * refill the budget.
 */
public class BudgetExhaustedException extends RuntimeException {

    private final long remaining;
    private final long limit;

    public BudgetExhaustedException(String message, long remaining, long limit) {
        super(message);
        this.remaining = remaining;
        this.limit = limit;
    }

    /** Tokens left when the refusal happened (0 when exhausted). */
    public long remaining() {
        return remaining;
    }

    /** The limit that was exhausted (-1 convention does not apply here: an
     * unlimited budget never throws). */
    public long limit() {
        return limit;
    }
}
