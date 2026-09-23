package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.extract.MemoryExtractWrite;
import io.github.qwzhang01.agent.memory.reconcile.MemoryDecisionListener;
import io.github.qwzhang01.agent.memory.reconcile.MemoryReconciler;

import java.util.List;

/**
 * Turns conversation messages into candidate {@link MemoryEntry}s, then
 * optionally writes them through {@link MemoryPolicy} into a {@link MemoryStore}.
 * <p>
 * Implementations only decide <em>what</em> to extract. They must not interpret
 * business-specific subjects. {@link #extractAndStore} is the shared write path.
 *
 * @see io.github.qwzhang01.agent.memory.extract.KeywordMemoryExtractor
 */
public interface MemoryExtractor {

    /**
     * Extract candidate entries. Not yet policy-gated or stored.
     *
     * @param messages conversation to scan
     * @param scope scope to store under
     * @param baseProvenance provenance template (actor + runId + at)
     */
    List<MemoryEntry> extract(List<ChatMessage> messages, String scope,
                              MemoryProvenance baseProvenance);

    /**
     * Reconciliation-aware extraction (memory route step 2): the extractor is
     * handed the recalled old entries so it can pick an existing subject key
     * instead of inventing a drifting one. Implementations that ignore the
     * evidence simply fall back to the 3-arg {@link #extract}.
     * <p>
     * The default implementation ignores {@code evidence} — backward compatible
     * for every extractor that has not opted into reconciliation.
     *
     * @param evidence recalled old entries (ACTIVE-only, same scope); may be empty
     */
    default List<MemoryEntry> extract(List<ChatMessage> messages, String scope,
                                      MemoryProvenance baseProvenance,
                                      List<MemoryEntry> evidence) {
        return extract(messages, scope, baseProvenance);
    }

    /**
     * Full write flow: extract → policy gate → supersede → store.
     *
     * @return number of entries actually stored
     */
    default int extractAndStore(List<ChatMessage> messages, String scope,
                                MemoryProvenance provenance, MemoryPolicy policy,
                                MemoryStore store) {
        return MemoryExtractWrite.run(this, messages, scope, provenance, policy, store);
    }

    /**
     * Full write flow with reconciliation (memory route step 2): recalls old
     * entries for the scope, feeds them to the extractor, applies the policy
     * gates, and reports each write decision through {@code decisionListener}.
     * <p>
     * Soft failure: when the reconciler cannot recall (store failure, no user
     * message), the flow degrades to the 5-arg path without evidence.
     */
    default int extractAndStore(List<ChatMessage> messages, String scope,
                                MemoryProvenance provenance, MemoryPolicy policy,
                                MemoryStore store,
                                MemoryReconciler reconciler,
                                MemoryDecisionListener decisionListener) {
        return MemoryExtractWrite.run(this, messages, scope, provenance, policy, store,
                reconciler, decisionListener);
    }
}

