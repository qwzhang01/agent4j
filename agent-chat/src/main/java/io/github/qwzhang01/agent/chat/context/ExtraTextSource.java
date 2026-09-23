package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Host-supplied atmosphere / narrative text. Injected as a system
 * message after whatever sources were registered before it.
 * <p>
 * Structured relationship views use {@link RelationSource}. Worldbook
 * entries use {@link LoreSource}.
 * <p>
 * Optionally bounded by a token budget ({@code maxTokens}). When the
 * text exceeds the budget, newline-delimited segments are dropped from
 * the <em>tail</em> (last-appended = lowest priority) until the result
 * fits. At least the first segment is always kept to avoid silently
 * zeroing out all instruction context. Tokens are approximated by
 * character count (1 char ≈ 1 token); swap {@link #estimateTokens} via
 * subclass to plug in a real tokenizer.
 * <p>
 * <b>Not safe to share across concurrent rooms/turns.</b> {@link #lastOutputBytes}
 * is a "last {@code contribute} call" snapshot used by
 * {@link io.github.qwzhang01.agent.chat.ChatEngine} to build {@code TurnTrace} right
 * after {@code contribute} returns on the same thread. Each {@code ChatRoom}/
 * {@code ChatEngine} must own its own {@code ExtraTextSource} instance; if the same
 * instance is registered on two rooms, or the same room's {@code stream} is invoked
 * concurrently from multiple threads, one turn's {@code TurnTrace} can observe another
 * turn's byte count.
 */
public class ExtraTextSource implements ContextSource {

    private static final Logger log = LoggerFactory.getLogger(ExtraTextSource.class);

    /** Sentinel value meaning "no budget enforced". */
    public static final int NO_LIMIT = -1;

    private final String text;
    /** {@link #NO_LIMIT} or a positive character budget. */
    private final int maxTokens;
    /**
     * UTF-8 byte size of the text emitted by the most recent {@link #contribute} call.
     * See the class-level thread-safety note: one instance = one room, one turn at a time.
     */
    private volatile int lastOutputBytes;

    /**
     * No token budget (backward-compatible constructor).
     */
    public ExtraTextSource(String text) {
        this(text, NO_LIMIT);
    }

    /**
     * @param text host-supplied text; {@code null} treated as empty
     * @param maxTokens positive character budget, or {@link #NO_LIMIT} ({@code -1})
     *                  for no limit
     * @throws IllegalArgumentException if {@code maxTokens} is 0 or any negative
     *                                  value other than {@code -1}
     */
    public ExtraTextSource(String text, int maxTokens) {
        if (maxTokens != NO_LIMIT && maxTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxTokens must be " + NO_LIMIT + " (no limit) or > 0, got: " + maxTokens);
        }
        this.text = text == null ? "" : text;
        this.maxTokens = maxTokens;
    }

    public int maxTokens() {
        return maxTokens;
    }

    /**
     * UTF-8 byte size of the text emitted during the most recent {@link #contribute} call.
     * Zero if the source has never been called or contributed nothing.
     * Used by {@link io.github.qwzhang01.agent.chat.ChatEngine} to populate
     * {@link io.github.qwzhang01.agent.core.agent.AgentEvent.TurnTrace#extraTextBytes}.
     */
    public int lastOutputBytes() {
        return lastOutputBytes;
    }

    @Override
    public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
        if (text.isBlank()) {
            lastOutputBytes = 0;
            return List.of();
        }
        String out = budget(text, maxTokens);
        lastOutputBytes = out.getBytes(StandardCharsets.UTF_8).length;
        if (out.isBlank()) {
            return List.of();
        }
        return List.of(ChatMessage.system(out));
    }

    /**
     * Applies the token budget to {@code raw}.
     * Drops newline-delimited segments from the tail, keeping the head.
     * At least the first segment is always retained even if it exceeds the budget.
     */
    private String budget(String raw, int limit) {
        if (limit == NO_LIMIT || estimateTokens(raw) <= limit) {
            return raw;
        }
        String[] lines = raw.split("\n", -1);
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (String line : lines) {
            int cost = line.length() + (kept.isEmpty() ? 0 : 1);  // +1 for the '\n' separator
            if (kept.isEmpty() || used + cost <= limit) {
                kept.add(line);
                used += cost;
            } else {
                break;
            }
        }
        int dropped = lines.length - kept.size();
        if (dropped > 0) {
            log.warn("ExtraTextSource budget exceeded: dropped {} line(s), kept {} / {} chars (maxTokens={})",
                    dropped, used, raw.length(), limit);
        }
        return String.join("\n", kept);
    }

    /**
     * Estimates the token count of {@code text} using character count (1 char ≈ 1 token).
     * <p>
     * This is a conservative approximation: for Latin text a real tokenizer would
     * produce ~4 chars per token; for Chinese/Japanese ~1.5 chars per token.
     * Override in a subclass to plug in a model-specific tokenizer.
     */
    protected int estimateTokens(String text) {
        return text.length();
    }
}
