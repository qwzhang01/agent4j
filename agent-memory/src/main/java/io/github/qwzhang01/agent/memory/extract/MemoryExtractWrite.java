package io.github.qwzhang01.agent.memory.extract;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryExtractor;
import io.github.qwzhang01.agent.memory.MemoryPolicy;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Shared extract → policy → store path for every {@link MemoryExtractor}.
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
                store.findActiveBySubject(candidate.scope(), candidate.subject())
                        .ifPresent(old -> {
                            store.update(old.withStatus(MemoryStatus.SUPERSEDED));
                            log.debug("Superseded old entry {} for subject {}", old.id(), old.subject());
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
