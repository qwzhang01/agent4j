package io.github.qwzhang01.agent.memory.context;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.ConversationAnchors;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.memory.MemoryType;
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
 *   <li>Inject: prepend memories to history; the loop adds the persona before this context</li>
 * </ol>
 * Memory injection is NOT written back to state (it's re-retrieved each turn).
 * <p>
 * <b>Layered injection (memory roadmap step 3).</b> The seven-arg constructor
 * enables Letta-style two-tier injection; the six-arg constructor keeps the
 * legacy single-block behaviour bit-for-bit (all existing call sites compile
 * and behave unchanged):
 * <ul>
 *   <li><b>Core tier</b> ({@code importance >= layering.coreImportanceThreshold()}):
 *       always-on working set. Injected at the head as {@code [Core memories]},
 *       never ranked by the query, never dropped by the token budget.</li>
 *   <li><b>Archival tier</b> (everything else): paged in beside the last USER
 *       message as {@code [Known memories]}, ranked by relevance to that
 *       message (the same anchor the WRITE side's reconciliation recall uses
 *       — {@link ConversationAnchors}), trimmed by the token budget.</li>
 * </ul>
 * Read-side query routing here is the read-side completion of step 1's
 * hybrid ranking: the semantic path now runs on the injection path, not only
 * inside {@code search_memory}.
 * <p>
 * Soft failure: any recall failure degrades this turn's injection to "no
 * memories" (log warn, return the history untouched). Injection is additive;
 * a broken memory read must never break the chat loop.
 */
public class MemoryContextBuilder implements ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(MemoryContextBuilder.class);

    private final MemoryRetriever retriever;
    private final List<String> scopes;
    private final ContextCompressor compressor;
    private final MemoryStore archiveStore;
    private final String archiveScope;
    private final int recallLimit;

    /** Layering policy; {@code null} = legacy single-block path (six-arg ctor). */
    private final MemoryLayering layering;
    /** Max archival-tier entries to page in (layered path only). */
    private final int archivalLimit;

    /**
     * Legacy single-block constructor: recall + importance-then-recency rank +
     * one {@code [Known memories]} USER message at the head. All existing call
     * sites (channel / tavern / enterprise / examples) compile and behave
     * unchanged — the layered path is opt-in.
     *
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
        this(retriever, scopes, compressor, archiveStore, archiveScope, recallLimit,
                null, 0);
    }

    /**
     * Layered constructor (roadmap step 3): core tier at the head
     * ({@code [Core memories]}), archival tier beside the last USER message
     * ({@code [Known memories]}), ranked by query relevance and trimmed by
     * the token budget.
     *
     * @param retriever     memory retriever
     * @param scopes        scopes visible in this context
     * @param compressor    optional compactor (null = no compaction)
     * @param archiveStore  optional store for compaction archives
     * @param archiveScope  scope for compaction archives
     * @param recallLimit   kept for source compatibility; the layered path
     *                      bounds archival with {@code archivalLimit} instead
     *                      (0 = no limit on archival)
     * @param layering      layering policy (core threshold + token budget);
     *                      {@code null} falls back to the legacy single block
     * @param archivalLimit max archival-tier entries to page in (0 = no limit)
     */
    public MemoryContextBuilder(MemoryRetriever retriever, List<String> scopes,
                                ContextCompressor compressor,
                                MemoryStore archiveStore, String archiveScope,
                                int recallLimit,
                                MemoryLayering layering,
                                int archivalLimit) {
        this.retriever = retriever;
        this.scopes = scopes;
        this.compressor = compressor;
        this.archiveStore = archiveStore;
        this.archiveScope = archiveScope;
        this.recallLimit = recallLimit;
        this.layering = layering;
        this.archivalLimit = archivalLimit;
    }

    @Override
    public List<ChatMessage> build(AgentConfig config, AgentState state) {
        return build(config, state, null);
    }

    /**
     * Stage 1.2 (harness roadmap): ctx-aware recall. When the run context
     * carries tenant/user/channel/agent identity, the recall scopes are
     * intersected with the context-derived scope whitelist: only scopes the
     * run's identity may see are actually queried. An anonymous context
     * (the auto-minted {@code RunContext.create()} with no identity fields)
     * keeps the configured scopes list as-is — same as the legacy no-ctx
     * path — so minting a runId does not silently drop channel memory.
     * <p>
     * This is the read-side hook for Stage 5's full tenant governance
     * (write-side audit + field redaction land there); today it guarantees
     * a context-bound run cannot widen its memory view by configuration.
     */
    @Override
    public List<ChatMessage> build(AgentConfig config, AgentState state,
                                   io.github.qwzhang01.agent.core.run.RunContext ctx) {
        List<String> effectiveScopes = scopes;
        if (ctx != null) {
            List<String> allowed = contextAllowedScopes(ctx);
            if (!allowed.isEmpty()) {
                effectiveScopes = scopes.stream()
                        .filter(allowed::contains)
                        .toList();
                if (effectiveScopes.isEmpty()) {
                    // No configured scope is visible to this identity: inject
                    // no memories rather than silently widening the view.
                    log.debug("All configured scopes filtered out by run context (tenant={}); "
                            + "injecting no memories", ctx.tenantId());
                    return new ArrayList<>(state.getMessages());
                }
            }
        }
        return doBuild(config, state, effectiveScopes);
    }

    /**
     * Scope whitelist derived from the run context. Empty means the context
     * carries no identity — skip filtering (anonymous / auto-minted runs).
     */
    private static List<String> contextAllowedScopes(
            io.github.qwzhang01.agent.core.run.RunContext ctx) {
        List<String> allowed = new ArrayList<>();
        if (ctx.tenantId() != null) {
            allowed.add("tenant:" + ctx.tenantId());
        }
        if (ctx.userId() != null) {
            allowed.add("user:" + ctx.userId());
        }
        if (ctx.channelId() != null) {
            allowed.add("channel:" + ctx.channelId());
        }
        if (ctx.agentId() != null) {
            allowed.add("agent:" + ctx.agentId());
        }
        return allowed;
    }

    private List<ChatMessage> doBuild(AgentConfig config, AgentState state, List<String> scopes) {
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

        // 2. Recall memories — soft failure: a broken recall degrades this
        // turn's injection to "no memories", never breaks the loop.
        List<MemoryEntry> memories;
        try {
            memories = recallLimit > 0
                    ? retriever.recallForContext(scopes, recallLimit)
                    : retriever.recall(scopes);
        } catch (RuntimeException e) {            log.warn("Memory recall failed; injecting no memories this turn: {}", e.getMessage());
            return new ArrayList<>(messages);
        }

        if (memories.isEmpty()) {
            return new ArrayList<>(messages);
        }

        // 3a. Legacy path: one block at the head (six-arg ctor behaviour).
        if (layering == null) {
            String memoryBlock = renderMemories(memories);
            List<ChatMessage> assembled = new ArrayList<>(messages.size() + 1);
            assembled.add(ChatMessage.user("[Known memories]\n" + memoryBlock));
            assembled.addAll(messages);
            log.debug("Injected {} memories into context (scopes={})", memories.size(), scopes);
            return assembled;
        }

        // 3b. Layered path: split by policy, rank archival by the query anchor,
        // assemble two blocks (core head, archival beside the turn).
        String anchor = ConversationAnchors.lastUserMessage(messages);
        List<MemoryEntry> core = new ArrayList<>();
        List<MemoryEntry> archival = new ArrayList<>();
        for (MemoryEntry m : memories) {
            (layering.isCore(m) ? core : archival).add(m);
        }
        if (anchor != null && !anchor.isBlank() && !archival.isEmpty()) {
            try {
                // Re-rank the whole visible pool by query relevance, keep the
                // non-core head of it, then cap at archivalLimit. This keeps the
                // ranking contract inside MemoryRetriever (one ranking authority).
                archival = retriever.recallForContext(scopes, 0, anchor)
                        .stream()
                        .filter(layering::isNotCore)
                        .limit(archivalLimit > 0 ? archivalLimit : Integer.MAX_VALUE)
                        .toList();
            } catch (RuntimeException e) {
                log.warn("Archival re-rank failed; falling back to importance order: {}",
                        e.getMessage());
            }
        }
        if (archivalLimit > 0 && archival.size() > archivalLimit) {
            archival = archival.subList(0, archivalLimit);
        }

        List<ChatMessage> assembled = LayeredMemoryAssembler.assemble(core, messages, archival, layering);
        log.debug("Layered injection: {} core + {} archival memories (scopes={})",
                core.size(), archival.size(), scopes);
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
