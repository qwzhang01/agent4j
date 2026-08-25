package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Concatenates {@link ContextSource}s in order. Compression is not
 * legislated here; a source may wrap a compressor if the host wants one.
 */
public final class ContextAssembler {

    private final List<ContextSource> sources;

    public ContextAssembler(List<ContextSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("at least one ContextSource is required");
        }
        this.sources = List.copyOf(sources);
    }

    public static ContextAssembler defaults() {
        return new ContextAssembler(List.of(new PersonaSource(), new HistorySource()));
    }

    public List<ContextSource> sources() {
        return sources;
    }

    public List<ChatMessage> assemble(Room room, ChatPersona speaker, String userText) {
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(speaker, "speaker");
        List<ChatMessage> messages = new ArrayList<>();
        for (ContextSource source : sources) {
            List<ChatMessage> chunk = source.contribute(room, speaker, userText);
            if (chunk != null && !chunk.isEmpty()) {
                messages.addAll(chunk);
            }
        }
        return List.copyOf(messages);
    }
}
