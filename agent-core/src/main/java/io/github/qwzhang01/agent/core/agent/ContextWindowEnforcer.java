package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link ContextBuilder} decorator that enforces a {@link ContextWindowBudget} (KP2).
 * <p>
 * The loop's {@code buildRequest()} assembles four components into the final model request:
 * <ol>
 *   <li>System prompt — prepended by the loop AFTER this builder runs.</li>
 *   <li>Tool / handoff schemas — appended by the loop AFTER this builder runs.</li>
 *   <li>History — produced by this builder (via the delegate).</li>
 *   <li>Output headroom — reserved for the model's reply; never explicitly placed.</li>
 * </ol>
 * Because (1) and (2) are added after {@code build()} returns, this class must
 * estimate their cost from the config and deduct them from the total budget to
 * compute the history budget.
 * <p>
 * Truncation strategy (v1): drop the oldest messages until the estimated history
 * cost fits within {@link ContextWindowBudget#historyBudget()}.
 * <ul>
 *   <li>Pairs are preserved: if a TOOL_RESULT is the oldest message, the preceding
 *       ASSISTANT+tool_calls message is dropped together with it to maintain the
 *       tool-call invariant required by all providers.</li>
 *   <li>The last message (most recent user turn) is never dropped — it is the
 *       reason for this invocation.</li>
 * </ul>
 * A WARN log is emitted every time truncation occurs, naming the messages dropped
 * and the estimated token savings.
 * <p>
 * When the delegate is {@code null}, the enforcer operates on {@code state.getMessages()}
 * directly (same passthrough-then-enforce semantics).
 * <p>
 * <b>Usage:</b>
 * <pre>{@code
 * ContextWindowBudget budget = ContextWindowBudget.window128k();
 * ContextBuilder base = new MyMemoryContextBuilder();
 * ContextBuilder enforced = new ContextWindowEnforcer(base, budget);
 *
 * AgentConfig config = new AgentConfig("demo", systemPrompt, model, tools, 10, enforced);
 * }</pre>
 */
public class ContextWindowEnforcer implements ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowEnforcer.class);

    private final ContextBuilder delegate;
    private final ContextWindowBudget budget;
    private final Consumer<ContextTrimRecord> trimListener;

    /**
     * @param delegate the underlying context builder; {@code null} means use raw state messages
     * @param budget   the four-account window budget to enforce
     */
    public ContextWindowEnforcer(ContextBuilder delegate, ContextWindowBudget budget) {
        this(delegate, budget, null);
    }

    /**
     * Stage 5.1: full constructor with a trim listener. Every truncation the
     * enforcer performs emits one {@link ContextTrimRecord} to the listener
     * (who / when / before-after counts), making the "who got cut" decision
     * queryable telemetry instead of a warn log only. {@code null} listener
     * = legacy behaviour exactly (warn log, no record).
     *
     * @param delegate     underlying context builder; {@code null} = raw state passthrough
     * @param budget       the four-account window budget to enforce
     * @param trimListener optional consumer of trim records; may be null
     */
    public ContextWindowEnforcer(ContextBuilder delegate, ContextWindowBudget budget,
                                 java.util.function.Consumer<ContextTrimRecord> trimListener) {
        this.delegate = delegate;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.trimListener = trimListener;
    }

    /** Convenience constructor: no delegate (raw state passthrough + enforcement). */
    public ContextWindowEnforcer(ContextWindowBudget budget) {
        this(null, budget);
    }

    @Override
    public List<ChatMessage> build(AgentConfig config, AgentState state) {
        List<ChatMessage> history = delegate != null
                ? delegate.build(config, state)
                : new ArrayList<>(state.getMessages());

        List<ChatMessage> trimmed = trimToBudget(history, budget.historyBudget());
        if (trimmed.size() != history.size()) {
            log.warn("[ContextWindowEnforcer] History truncated: {} → {} messages, " +
                            "est. tokens {} → {} (budget={})",
                    history.size(), trimmed.size(),
                    estimateTokens(history), estimateTokens(trimmed), budget.historyBudget());
            if (trimListener != null) {
                String agentName = config != null ? config.getName() : null;
                trimListener.accept(ContextTrimRecord.of(
                        agentName,
                        ContextTrimRecord.TrimSource.ENFORCER,
                        history.size(), trimmed.size(),
                        estimateTokens(history), estimateTokens(trimmed),
                        budget.historyBudget()));
            }
        }
        return trimmed;
    }

    /**
     * Drop oldest logical units until {@code history} fits {@code historyBudget}.
     * Shared by {@link HandoffInputFilter#keepWithin(ContextWindowBudget)} so
     * handoff hops and per-turn enforcement use the same pair-preserving rule.
     * The last message is never dropped. Returns the input list when already fit.
     */
    public static List<ChatMessage> trimToBudget(List<ChatMessage> history, int historyBudget) {
        if (history == null || history.isEmpty()) {
            return history == null ? List.of() : history;
        }
        if (estimateTokens(history) <= historyBudget) {
            return history;
        }
        List<ChatMessage> mutable = new ArrayList<>(history);
        dropOldestUntilFits(mutable, historyBudget);
        return mutable;
    }

    /**
     * Drops the oldest messages one logical unit at a time until the estimate fits.
     * <p>
     * A "logical unit" is:
     * <ul>
     *   <li>A single message for USER, SYSTEM, and standalone ASSISTANT messages.</li>
     *   <li>An ASSISTANT-with-tool-calls + its following TOOL messages as a pair,
     *       because providers reject a tool-result message without its preceding
     *       assistant tool-call message.</li>
     * </ul>
     * The last message is never dropped.
     */
    private static void dropOldestUntilFits(List<ChatMessage> messages, int budget) {
        while (messages.size() > 1 && estimateTokens(messages) > budget) {
            // Identify how many messages form the oldest logical unit
            int unitSize = oldestUnitSize(messages);
            if (unitSize >= messages.size()) {
                // Cannot drop the last unit without removing the current user message
                break;
            }
            messages.subList(0, unitSize).clear();
        }
    }

    /**
     * Returns the size of the oldest logical unit in the list.
     * <p>
     * If the oldest message is an ASSISTANT message that has tool calls, the unit
     * also includes all immediately following TOOL messages.
     */
    private static int oldestUnitSize(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return 0;
        }
        ChatMessage oldest = messages.get(0);
        // ASSISTANT with tool calls: unit = assistant + all trailing tool results
        if (oldest.role() == io.github.qwzhang01.agent.core.model.ChatRole.ASSISTANT
                && oldest.toolCalls() != null && !oldest.toolCalls().isEmpty()) {
            int size = 1;
            while (size < messages.size()
                    && messages.get(size).role() == io.github.qwzhang01.agent.core.model.ChatRole.TOOL) {
                size++;
            }
            return size;
        }
        return 1;
    }

    /**
     * Estimate the token cost of a list of messages using the {@code chars / 4} heuristic.
     * This is intentionally approximate and consistent with {@code ContextBudget} in agent-memory.
     */
    static int estimateTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int chars = 0;
        for (ChatMessage msg : messages) {
            if (msg.content() != null) {
                chars += msg.content().length();
            }
            if (msg.toolCalls() != null) {
                for (var tc : msg.toolCalls()) {
                    if (tc.name() != null) chars += tc.name().length();
                    if (tc.arguments() != null) chars += tc.arguments().toString().length();
                }
            }
        }
        return chars / 4;
    }

    /**
     * Estimate the token cost of a single string (system prompt or schema JSON).
     */
    static int estimateTokens(String text) {
        return text == null ? 0 : text.length() / 4;
    }

    /**
     * Estimate the combined token cost of all tool and handoff schemas.
     * Used for informational logging only; not deducted from historyBudget at runtime
     * (that is the job of {@link ContextWindowBudget#toolSchemaReserve()}).
     */
    static int estimateToolSchemas(ToolRegistry registry, List<HandoffSpec> handoffs) {
        int chars = 0;
        if (registry != null) {
            for (String schema : registry.getToolSchemas()) {
                chars += schema.length();
            }
        }
        if (handoffs != null) {
            for (HandoffSpec spec : handoffs) {
                chars += spec.toolSchema().length();
            }
        }
        return chars / 4;
    }

    /** Returns the budget this enforcer is configured with. */
    public ContextWindowBudget getBudget() {
        return budget;
    }

    /** Returns the delegate builder (may be null for passthrough mode). */
    public ContextBuilder getDelegate() {
        return delegate;
    }

    /** Returns the trim listener (may be null = legacy warn-log-only behaviour). */
    public java.util.function.Consumer<ContextTrimRecord> getTrimListener() {
        return trimListener;
    }
}
