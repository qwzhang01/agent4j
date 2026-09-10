package io.github.qwzhang01.agent.memory.extract;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.MemoryDecision;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryExtractor;
import io.github.qwzhang01.agent.memory.MemoryLifecycle;
import io.github.qwzhang01.agent.memory.MemoryPolicy;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.memory.reconcile.MemoryDecisionListener;
import io.github.qwzhang01.agent.memory.reconcile.MemoryReconciler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Shared extract → reconcile → policy → store path for every {@link MemoryExtractor}.
 * <p>
 * When a candidate replaces an existing same-subject entry, the old entry's
 * fate is decided by {@link MemoryLifecycle#supersedeTarget}: EVOLVE candidates
 * keep the old entry queryable as HISTORICAL (it was once true), everything
 * else (CONFLICT or not judged) archives it as SUPERSEDED.
 * <p>
 * <b>Reconciliation (memory route step 2)</b>: the 8-arg {@link #run} recalls
 * up to {@link MemoryReconciler#RECALL_LIMIT} old entries for the scope, feeds
 * them to the extractor as evidence (read side feeds the write side), and
 * stamps the bi-temporal axes on every supersede: the old entry's
 * {@code validAt} = the new entry's business start (its {@code validFrom},
 * falling back to {@code createdAt} — the user moved last week, not this
 * Monday), and its {@code invalidAt} = now (the ledger closes the line now).
 * Every write decision is reported to the {@link MemoryDecisionListener}.
 */
public final class MemoryExtractWrite {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private MemoryExtractWrite() {
    }

    /**
     * Legacy path (no reconciliation, no decision reporting): kept for the
     * 5-arg {@code extractAndStore} and all existing call sites.
     */
    public static int run(MemoryExtractor extractor, List<ChatMessage> messages, String scope,
                   MemoryProvenance provenance, MemoryPolicy policy, MemoryStore store) {
        return run(extractor, messages, scope, provenance, policy, store, null, null);
    }

    /**
     * Reconciliation path (memory route step 2).
     *
     * @param reconciler       recalls old entries as extraction evidence;
     *                         {@code null} = no reconciliation (plain 5-arg behaviour)
     * @param decisionListener receives one {@link MemoryDecision} per write decision;
     *                         {@code null} = no reporting
     */
    public static int run(MemoryExtractor extractor, List<ChatMessage> messages, String scope,
                   MemoryProvenance provenance, MemoryPolicy policy, MemoryStore store,
                   MemoryReconciler reconciler, MemoryDecisionListener decisionListener) {
        List<MemoryEntry> evidence = reconciler == null
                ? List.of()
                : reconciler.recallEvidence(messages, scope);
        List<MemoryEntry> candidates = extractor.extract(messages, scope, provenance, evidence);
        int stored = 0;
        Instant decidedAt = Instant.now();
        for (MemoryEntry candidate : candidates) {
            if (!policy.shouldStore(candidate, store)) {
                log.debug("Policy rejected candidate: {}", candidate.subject());
                report(decisionListener, MemoryDecision.reject(
                        candidate.subject(), actorOf(provenance), decidedAt, Instant.now()),
                        store);
                continue;
            }
            if (policy.shouldSupersede(candidate, store)) {
                MemoryStatus target = MemoryLifecycle.supersedeTarget(candidate.lifecycle());
                Optional<MemoryEntry> oldOpt = store.findActiveBySubject(candidate.scope(), candidate.subject());
                oldOpt.ifPresent(old -> {
                    // Bi-temporal close: business axis ends at the new fact's business start;
                    // system axis ends now. Never stamp validAt with wall-clock now.
                    Instant newBusinessStart = candidate.validFrom() != null
                            ? candidate.validFrom()
                            : candidate.createdAt();
                    store.update(old.closedAs(target, newBusinessStart, Instant.now()));
                    log.debug("Marked old entry {} as {} for subject {} (validAt={}, invalidAt={})",
                            old.id(), target, old.subject(), newBusinessStart, Instant.now());
                });
                MemoryStatus defaultStatus = policy.defaultStatusForScope(candidate.scope());
                MemoryEntry written = store.write(candidate.withStatus(defaultStatus));
                stored++;
                report(decisionListener, MemoryDecision.update(
                        candidate.subject(), candidate.lifecycle(),
                        oldOpt.map(MemoryEntry::id).orElse(null),
                        written.id(), actorOf(provenance), decidedAt, Instant.now()),
                        store);
                continue;
            }
            MemoryStatus defaultStatus = policy.defaultStatusForScope(candidate.scope());
            MemoryEntry written = store.write(candidate.withStatus(defaultStatus));
            stored++;
            report(decisionListener, MemoryDecision.add(
                    candidate.subject(), written.id(), actorOf(provenance), decidedAt, Instant.now()),
                    store);
        }
        log.info("Extracted {} candidates, stored {}", candidates.size(), stored);
        return stored;
    }

    private static void report(MemoryDecisionListener listener, MemoryDecision decision,
                               MemoryStore store) {
        if (listener == null) {
            return;
        }
        try {
            listener.onDecision(decision, store);
        } catch (RuntimeException e) {
            log.warn("Decision listener failed for subject {}: {}",
                    decision.subject(), e.getMessage());
        }
    }

    private static String actorOf(MemoryProvenance provenance) {
        return provenance == null ? null : provenance.actor();
    }
}
