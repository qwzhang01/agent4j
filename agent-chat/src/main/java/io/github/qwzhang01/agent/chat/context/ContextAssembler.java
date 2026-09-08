package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
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

    /**
     * Separate active persona, conversation history and request-only context.
     * PersonaSource supplies AgentConfig; HistorySource supplies AgentState.
     * Other sources stay transient, preserving their order and roles in prefix.
     */
    public PreparedContext prepare(Room room, ChatPersona speaker, String userText) {
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(speaker, "speaker");
        String persona = null;
        boolean hasPersona = false;
        List<ChatMessage> prefix = new ArrayList<>();
        List<ChatMessage> history = new ArrayList<>();
        for (ContextSource source : sources) {
            if (source instanceof PersonaSource) {
                if (hasPersona) {
                    throw new IllegalArgumentException("Only one PersonaSource may be configured");
                }
                hasPersona = true;
                persona = speaker.systemPrompt();
                continue;
            }
            List<ChatMessage> chunk = source.contribute(room, speaker, userText);
            if (chunk != null && !chunk.isEmpty()) {
                prefix.addAll(chunk);
                if (source instanceof HistorySource) {
                    history.addAll(chunk);
                }
            }
        }
        return new PreparedContext(persona, List.copyOf(prefix), List.copyOf(history));
    }

    /** Immutable run inputs; prefix excludes persona but includes visible history. */
    public record PreparedContext(String systemPrompt, List<ChatMessage> prefix,
                                  List<ChatMessage> history) {
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
