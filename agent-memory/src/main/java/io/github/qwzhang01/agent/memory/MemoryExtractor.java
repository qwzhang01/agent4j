package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.extract.MemoryExtractWrite;

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
     * @param messages       conversation to scan
     * @param scope          scope to store under
     * @param baseProvenance provenance template (actor + runId + at)
     */
    List<MemoryEntry> extract(List<ChatMessage> messages, String scope,
                              MemoryProvenance baseProvenance);

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
}
