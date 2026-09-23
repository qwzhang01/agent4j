package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.List;
import java.util.Objects;

/**
 * Filters the history the <em>next</em> agent sees after a handoff (KP2 / Decision 24 P2).
 * <p>
 * Applied at the model-request boundary only. {@link AgentState} is the audit
 * ledger and is never rewritten — identity is configuration, history is fact.
 * This is the OpenAI Agents SDK {@code input_filter} shape: three carry strategies,
 * of which v1 ships two plus identity.
 * <pre>
 *   IDENTITY — full history (Decision 24 P1 default)
 *   keepWithin — drop oldest units until the target's history budget fits
 *   lastTurn — from the last USER message inclusive (task, not the whole call)
 * </pre>
 * Summary-carry (the third KP1 strategy) is not a filter: it needs a compressor
 * that writes a frozen summary. Compose that as a {@link ContextBuilder} on the
 * target instead of inventing a second compaction path here.
 */
@FunctionalInterface
public interface HandoffInputFilter {

    /** Full carry. Same object the loop uses before any handoff. */
    HandoffInputFilter IDENTITY = (history, from, to) -> history;

    /**
     * @param history conversation so far (persona already excluded)
     * @param from config that initiated the transfer; {@code null} only if
     *                a host calls the filter outside a handoff
     * @param to config that will run the next step
     * @return messages the next model request should carry; must not be null
     */
    List<ChatMessage> filter(List<ChatMessage> history, AgentConfig from, AgentConfig to);

    /**
     * Trim to {@code budget.historyBudget} with the same pair-preserving
     * rule as {@link ContextWindowEnforcer}.
     */
    static HandoffInputFilter keepWithin(ContextWindowBudget budget) {
        Objects.requireNonNull(budget, "budget");
        return (history, from, to) -> ContextWindowEnforcer.trimToBudget(history, budget.historyBudget());
    }

    /**
     * Keep from the last USER message through the end (handoff tool pair included).
     * If there is no USER turn, keep the last message so the request is never empty.
     */
    static HandoffInputFilter lastTurn() {
        return (history, from, to) -> {
            if (history == null || history.isEmpty()) {
                return history == null ? List.of() : history;
            }
            int lastUser = -1;
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).role() == ChatRole.USER) {
                    lastUser = i;
                    break;
                }
            }
            if (lastUser < 0) {
                return List.of(history.get(history.size() - 1));
            }
            return List.copyOf(history.subList(lastUser, history.size()));
        };
    }
}
