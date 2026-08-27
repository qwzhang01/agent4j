package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Optional worldbook slice: host-supplied entries fire when the current
 * user line matches a keyword or regex.
 * <p>
 * Not registered by {@link ContextAssembler#defaults()} or
 * {@link io.github.qwzhang01.agent.chat.ChatRoom.Builder} unless the host
 * calls {@code .source(new LoreSource(...))}. Calling {@code .source()}
 * replaces the default Persona + History pair; register those explicitly
 * if they are still wanted.
 * <p>
 * Matching uses this turn's {@code userText} only — not room history.
 * Vocabularies and card formats (SillyTavern lorebook, etc.) stay in the
 * product or an import tool.
 */
public final class LoreSource implements ContextSource {

    static final String HEADER = "[Lore]";

    private final List<LoreEntry> entries;
    private final int limit;

    public LoreSource(LoreEntry... entries) {
        this(entries == null ? List.of() : Arrays.asList(entries), 0);
    }

    /**
     * Inject every matching entry (no cut-off).
     */
    public LoreSource(List<LoreEntry> entries) {
        this(entries, 0);
    }

    /**
     * @param limit max matching entries in registration order;
     *              {@code <= 0} means no cut-off
     */
    public LoreSource(List<LoreEntry> entries, int limit) {
        this.entries = copy(entries);
        this.limit = limit;
    }

    public List<LoreEntry> entries() {
        return entries;
    }

    public int limit() {
        return limit;
    }

    @Override
    public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
        List<String> hits = new ArrayList<>();
        for (LoreEntry entry : entries) {
            if (limit > 0 && hits.size() >= limit) {
                break;
            }
            if (entry.content().isBlank()) {
                continue;
            }
            if (entry.trigger().matches(userText)) {
                hits.add(entry.content());
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }
        return List.of(ChatMessage.system(render(hits)));
    }

    private static String render(List<String> hits) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String hit : hits) {
            sb.append("- ").append(hit).append('\n');
        }
        return sb.toString().trim();
    }

    private static List<LoreEntry> copy(List<LoreEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<LoreEntry> copy = new ArrayList<>();
        for (LoreEntry entry : entries) {
            if (entry != null) {
                copy.add(entry);
            }
        }
        return copy.isEmpty() ? List.of() : List.copyOf(copy);
    }
}
