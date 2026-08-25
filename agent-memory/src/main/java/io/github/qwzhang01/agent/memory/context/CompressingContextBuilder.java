package io.github.qwzhang01.agent.memory.context;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Context builder that enforces a token budget via pi-style compaction (Stage 8 D4/D5).
 * <p>
 * Flow:
 * <ol>
 *   <li>Read {@code state.getMessages()}</li>
 *   <li>If estimated tokens exceed budget -> {@link ContextCompressor} summarizes
 *       the oldest messages into one summary, keeping system + recent K</li>
 *   <li>Rewrite {@code state.getMessages()} in place (checkpoint consistency)</li>
 *   <li>Archive the original compressed messages to {@link MemoryStore} as a
 *       {@link MemoryType#SUMMARY} entry (if store + scope configured)</li>
 * </ol>
 * <p>
 * Memory retrieval injection is added in Stage 8 M8.3.
 */
public class CompressingContextBuilder implements ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(CompressingContextBuilder.class);

    private final ContextCompressor compressor;
    private final MemoryStore archiveStore;
    private final String archiveScope;

    /**
     * @param modelClient  model used for summarization
     * @param budgetTokens token budget before compaction triggers
     * @param keepRecent   messages to keep verbatim at the tail
     * @param archiveStore optional store for archiving compressed originals (null = no archive)
     * @param archiveScope scope under which to archive summaries (null = no archive)
     */
    public CompressingContextBuilder(ModelClient modelClient, int budgetTokens, int keepRecent,
                                     MemoryStore archiveStore, String archiveScope) {
        this.compressor = new ContextCompressor(modelClient, budgetTokens, keepRecent);
        this.archiveStore = archiveStore;
        this.archiveScope = archiveScope;
    }

    @Override
    public List<ChatMessage> build(AgentConfig config, AgentState state) {
        List<ChatMessage> messages = state.getMessages();

        ContextCompressor.CompressionResult result = compressor.compress(messages);

        if (!result.didCompress()) {
            return new ArrayList<>(messages);
        }

        // Rewrite state in place (Stage 8 D4: checkpointed state matches what was sent)
        messages.clear();
        messages.addAll(result.compressed());

        // Archive the original messages that were folded into the summary
        if (archiveStore != null && archiveScope != null && !result.archived().isEmpty()) {
            String archivedText = renderArchived(result.archived());
            archiveStore.write(new MemoryEntry(
                    null,
                    archiveScope,
                    MemoryType.SUMMARY,
                    "compaction-" + Instant.now().toEpochMilli(),
                    archivedText,
                    0.3,
                    MemoryProvenance.modelDerived(
                            config != null ? config.getName() : "unknown", null, Instant.now()),
                    MemoryStatus.ACTIVE,
                    Instant.now(),
                    null
            ));
            log.debug("Archived {} compressed messages as SUMMARY in scope {}",
                    result.archived().size(), archiveScope);
        }

        return new ArrayList<>(result.compressed());
    }

    private String renderArchived(List<ChatMessage> archived) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : archived) {
            sb.append("[").append(msg.role()).append("] ");
            if (msg.content() != null) {
                sb.append(msg.content());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
