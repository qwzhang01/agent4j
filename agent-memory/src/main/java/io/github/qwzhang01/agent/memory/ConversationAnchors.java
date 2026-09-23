package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.List;

/**
 * Shared query-anchor definition for the memory pipeline (roadmap step 3).
 * <p>
 * ONE definition for both sides of the pipeline so they can never drift:
 * the WRITE side ({@code MemoryReconciler} evidence recall) and the READ side
 * ({@code MemoryContextBuilder} archival relevance) anchor on the SAME
 * conversation turn — the last non-blank USER message. The turn the writer
 * compares old entries against is the turn the reader ranks relevance ;
 * two private copies of this rule would eventually diverge (the same reason
 * {@code MemoryEntry.embedText} is defined once for write and read).
 * <p>
 * Multimodal-only USER messages (parts, null content) carry no text anchor
 * and are skipped.
 */
public final class ConversationAnchors {

    private ConversationAnchors() {
    }

    /**
     * The last USER message with non-blank text content, searching backwards.
     *
     * @return the anchor text, or {@code null} when no such message exists
     */
    public static String lastUserMessage(List<ChatMessage> messages) {
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
