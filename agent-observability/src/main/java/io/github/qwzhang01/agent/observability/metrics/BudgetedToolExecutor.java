package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.observability.cost.BudgetBook;
import io.github.qwzhang01.agent.observability.cost.BudgetCheck;
import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Tool boundary with the budget wired in (, the tool-side twin
 * of {@link BudgetedModelClient}): every tool call consumes the RUN
 * budget's tool-call axis when the host configured a per-run
 * {@code maxToolCalls} limit.
 * <p>
 * v1 tool-cost model: one tool call = 1 unit on the RUN dimension
 * (counted against the run's tool-call budget). Token-denominated tool
 * costs (a search API returning 8k tokens of context) are NOT invented
 * here - the host prices its own tools and records them via
 * {@code BudgetBook.recordUsage}; this decorator enforces the call-count
 * circuit breaker, the axis 's fix-loop trench taught us to hold.
 * <p>
 * Denial text, not exception: the loop's error discipline turns executor
 * throws into generic failures the model sees as broken tools; a budget
 * refusal is a policy statement, so it surfaces as a
 * {@code [DENIED] budget exhausted...} result the model can relay to the
 * user (same text-prefix contract ObservingToolExecutor reads denials
 * by). Wrap OUTSIDE the governed chain to keep denial detection working.
 */
public final class BudgetedToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BudgetedToolExecutor.class);

    /** Governance-chain denial prefix (ObservingToolExecutor contract). */
    static final String DENIED_PREFIX = "[DENIED] ";

    private final ToolExecutor delegate;
    private final BudgetBook book;
    private final String runToolBudgetKey;

    private BudgetedToolExecutor(ToolExecutor delegate, BudgetBook book, String runToolBudgetKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.book = Objects.requireNonNull(book, "book");
        this.runToolBudgetKey = Objects.requireNonNull(runToolBudgetKey, "runToolBudgetKey");
    }

    /**
     * Wrap with a dedicated run-scoped tool budget key. The host configures
     * {@code book.budget(RUN, key, maxCalls)} for each run's tool budget.
     */
    public static BudgetedToolExecutor wrap(ToolExecutor delegate, BudgetBook book,
                                            String runToolBudgetKey) {
        return new BudgetedToolExecutor(delegate, book, runToolBudgetKey);
    }

    @Override
    public String execute(ToolCall toolCall) {
        return delegate.execute(toolCall);  // no ctx: no gate (legacy)
    }

    @Override
    public String execute(ToolCall toolCall, RunContext ctx) {
        if (ctx == null) {
            return delegate.execute(toolCall, ctx);
        }
        BudgetCheck check = book.requireBudget(BudgetDimension.RUN, runToolBudgetKey, 1);
        if (check instanceof BudgetCheck.Denied denied) {
            return DENIED_PREFIX + "budget exhausted: run tool-call limit "
                    + denied.limitTokens() + " reached (used " + denied.usedTokens() + ")";
        }
        String result = delegate.execute(toolCall, ctx);
        try {
            book.recordUsage(BudgetDimension.RUN, runToolBudgetKey, 1);
        } catch (RuntimeException e) {
            log.warn("tool usage recording failed (side channel, swallowing): {}", e.toString());
        }
        return result;
    }
}
