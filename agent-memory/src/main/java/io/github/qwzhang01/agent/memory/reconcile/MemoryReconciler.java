package io.github.qwzhang01.agent.memory.reconcile;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryExtractor;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryStore;

import java.util.List;
import java.util.Objects;

/**
 * Reconciliation context provider: feeds the write side with read-side
 * recalled entries ("read side feeds the write side", memory route step 2).
 * <p>
 * Root cause it removes: the extractor is blind to old entries — it invents a
 * subject key from the conversation alone, so a drifted key ("moving" vs
 * "home-city") never matches the old entry and supersede never triggers. The
 * reconciler recalls up to {@link #RECALL_LIMIT} entries relevant to the last
 * user message and hands the "subject: content" list to the extractor via the
 * {@link MemoryExtractor#extract(List, String, MemoryEntry...)} prompt.
 * <p>
 * No new store surface: recall goes through the store's existing query path
 * ({@link MemoryRetriever#recallForContext(List, int, String)}), ranking
 * included. If recall fails or returns nothing, extraction proceeds exactly
 * as before (soft failure: the loop is additive, never a hard dependency).
 * <p>
 * Visibility rules (who may see what): recall is scoped to the SAME scope the
 * candidate will be written under — cross-scope old accounts are invisible,
 * preserving scope isolation. ACTIVE-only: closed lines are not evidence for
 * new judgments (a closed account cannot testify).
 */
public final class MemoryReconciler {

    /** How many old entries to recall for the reconciliation prompt. */
    public static final int RECALL_LIMIT = 20;

    private final MemoryStore store;
    private final MemoryRetriever retriever;

    /**
     * @param store     the store old entries live in (scope isolation applies)
     * @param retriever the retriever used for recall (ranking strategy included)
     */
    public MemoryReconciler(MemoryStore store, MemoryRetriever retriever) {
        this.store = Objects.requireNonNull(store, "store");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
    }

    /**
     * Recall the reconciliation evidence for a candidate write under
     * {@code scope}: up to {@link #RECALL_LIMIT} ACTIVE entries ranked by
     * relevance to the last user message in {@code messages}.
     * <p>
     * Soft failure: any runtime failure during recall returns an empty list
     * and the pipeline proceeds without evidence (extraction degrades to the
     * pre-reconciliation behaviour, it does not crash).
     */
    public List<MemoryEntry> recallEvidence(List<ChatMessage> messages, String scope) {
        String query = lastUserMessage(messages);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return retriever.recallForContext(List.of(scope), RECALL_LIMIT, query);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Render recalled entries as the prompt block the extractor sees.
     */
    public static String renderEvidencePrompt(List<MemoryEntry> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "Existing subjects: (none)";
        }
        StringBuilder sb = new StringBuilder("Existing subjects:\n");
        for (MemoryEntry e : evidence) {
            sb.append("- ").append(e.subject()).append(": ").append(e.content()).append('\n');
        }
        return sb.toString();
    }

    private static String lastUserMessage(List<ChatMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg.role() == ChatRole.USER && msg.content() != null && !msg.content().isBlank()) {
                return msg.content();
            }
        }
        return null;
    }
}
