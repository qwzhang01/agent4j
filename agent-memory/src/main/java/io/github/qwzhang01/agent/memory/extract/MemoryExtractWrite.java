package io.github.qwzhang01.agent.memory.extract;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryExtractor;
import io.github.qwzhang01.agent.memory.MemoryLifecycle;
import io.github.qwzhang01.agent.memory.MemoryPolicy;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Shared extract → policy → store path for every {@link MemoryExtractor}.
 * <p>
 * When a candidate replaces an existing same-subject entry, the old entry's
 * fate is decided by {@link MemoryLifecycle#supersedeTarget}: EVOLVE candidates
 * keep the old entry queryable as HISTORICAL (it was once true), everything
 * else (CONFLICT or not judged) archives it as SUPERSEDED.
 */
public final class MemoryExtractWrite {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private MemoryExtractWrite() {
    }

    public static int run(MemoryExtractor extractor, List<ChatMessage> messages, String scope,
                   MemoryProvenance provenance, MemoryPolicy policy, MemoryStore store) {
        List<MemoryEntry> candidates = extractor.extract(messages, scope, provenance);
        int stored = 0;
        for (MemoryEntry candidate : candidates) {
            if (!policy.shouldStore(candidate, store)) {
                log.debug("Policy rejected candidate: {}", candidate.subject());
                continue;
            }
            if (policy.shouldSupersede(candidate, store)) {
                MemoryStatus target = MemoryLifecycle.supersedeTarget(candidate.lifecycle());
                store.findActiveBySubject(candidate.scope(), candidate.subject())
                        .ifPresent(old -> {
                            store.update(old.withStatus(target));
                            log.debug("Marked old entry {} as {} for subject {}",
                                    old.id(), target, old.subject());
                        });
            }
            MemoryStatus defaultStatus = policy.defaultStatusForScope(candidate.scope());
            store.write(candidate.withStatus(defaultStatus));
            stored++;
        }
        log.info("Extracted {} candidates, stored {}", candidates.size(), stored);
        return stored;
    }
}
