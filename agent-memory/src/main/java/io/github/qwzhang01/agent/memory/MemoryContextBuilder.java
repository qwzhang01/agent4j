package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-featured context builder combining memory retrieval + compaction (Stage 8 M8.3).
 * <p>
 * Flow:
 * <ol>
 *   <li>Compact: if messages exceed budget, summarize old messages (keeps state consistent)</li>
 *   <li>Recall: retrieve active memories visible from the configured scopes</li>
 *   <li>Inject: render memories as a context block right after the system prompt</li>
 * </ol>
 * Memory injection is NOT written back to state (it's re-retrieved each turn).
 */
public class MemoryContextBuilder implements ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(MemoryContextBuilder.class);

    private final MemoryRetriever retriever;
    private final List<String> scopes;
    private final ContextCompressor compressor;
    private final MemoryStore archiveStore;
    private final String archiveScope;
    private final int recallLimit;

    /**
     * @param retriever    memory retriever
     * @param scopes       scopes visible in this context (e.g. [user:u1, channel:c1])
     * @param compressor   optional compactor (null = no compaction)
     * @param archiveStore optional store for compaction archives
     * @param archiveScope scope for compaction archives
     * @param recallLimit  max memories to inject (0 = no limit)
     */
    public MemoryContextBuilder(MemoryRetriever retriever, List<String> scopes,
                                ContextCompressor compressor,
                                MemoryStore archiveStore, String archiveScope,
                                int recallLimit) {
        this.retriever = retriever;
        this.scopes = scopes;
        this.compressor = compressor;
        this.archiveStore = archiveStore;
        this.archiveScope = archiveScope;
        this.recallLimit = recallLimit;
    }

    @Override
    public List<ChatMessage> build(AgentConfig config, AgentState state) {
        List<ChatMessage> messages = state.getMessages();

        // 1. Compaction (rewrites state in place if triggered)
        if (compressor != null) {
            var result = compressor.compress(messages);
            if (result.didCompress()) {
                messages.clear();
                messages.addAll(result.compressed());
                archive(result.archived(), config);
            }
        }

        // 2. Recall memories
        List<MemoryEntry> memories = recallLimit > 0
                ? retriever.recallForContext(scopes, recallLimit)
                : retriever.recall(scopes);

        if (memories.isEmpty()) {
            return new ArrayList<>(messages);
        }

        // 3. Inject memories after the system prompt
        String memoryBlock = renderMemories(memories);
        List<ChatMessage> assembled = new ArrayList<>();
        boolean injected = false;
        for (ChatMessage m : messages) {
            assembled.add(m);
            if (!injected && m.role() == ChatRole.SYSTEM) {
                assembled.add(ChatMessage.user("[Known memories]\n" + memoryBlock));
                injected = true;
            }
        }
        if (!injected) {
            assembled.add(0, ChatMessage.user("[Known memories]\n" + memoryBlock));
        }

        log.debug("Injected {} memories into context (scopes={})", memories.size(), scopes);
        return assembled;
    }

    private String renderMemories(List<MemoryEntry> memories) {
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry m : memories) {
            sb.append("- [").append(m.type()).append("] ");
            if (m.subject() != null) {
                sb.append(m.subject()).append(": ");
            }
            sb.append(m.content()).append("\n");
        }
        return sb.toString().trim();
    }

    private void archive(List<ChatMessage> archived, AgentConfig config) {
        if (archiveStore == null || archiveScope == null || archived.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : archived) {
            sb.append("[").append(m.role()).append("] ");
            if (m.content() != null) sb.append(m.content());
            sb.append("\n");
        }
        archiveStore.write(new MemoryEntry(
                null, archiveScope, MemoryType.SUMMARY,
                "compaction-" + Instant.now().toEpochMilli(),
                sb.toString().trim(), 0.3,
                MemoryProvenance.modelDerived(
                        config != null ? config.getName() : "unknown", null, Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null
        ));
    }
}
