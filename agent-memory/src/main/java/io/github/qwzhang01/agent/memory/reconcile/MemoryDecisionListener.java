package io.github.qwzhang01.agent.memory.reconcile;

import io.github.qwzhang01.agent.memory.MemoryDecision;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryStore;

import java.util.List;

/**
 * Observer hook for {@link MemoryDecision} events: every supersede-capable
 * write path (extract pipeline, save_memory tool, admin approve) reports each
 * decision through the listener chain after the write lands. Hosts that need
 * persistent decision history (audit tables, dashboards) implement this and
 * register it on the pipeline entry points.
 * <p>
 * Listener failures must never break the write path: implementations are called
 * after the store mutation, and exceptions are caught and logged by the caller.
 */
public interface MemoryDecisionListener {

    /**
     * Called once per write decision, after the decision has been applied to
     * the store. {@code decision.appliedAt()} is already stamped.
     */
    void onDecision(MemoryDecision decision, MemoryStore store);

    /**
     * No-op default composite: chains this listener after another.
     */
    default MemoryDecisionListener andThen(MemoryDecisionListener next) {
        return (decision, store) -> {
            onDecision(decision, store);
            next.onDecision(decision, store);
        };
    }
}
