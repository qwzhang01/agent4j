package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.RelationSnapshot;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Optional relationship slice: inject a host-supplied {@link RelationSnapshot}.
 * <p>
 * Not registered by {@link ContextAssembler#defaults()} or
 * {@link io.github.qwzhang01.agent.chat.ChatRoom.Builder} unless the host
 * calls {@code .source(new RelationSource(...))}. Calling {@code .source()}
 * replaces the default Persona + History pair; register those explicitly
 * if they are still wanted.
 * <p>
 * The engine does not advance stages, apply formulas, or depend on
 * {@code agent-tavern}. A pre-rendered blob can still go through
 * {@link ExtraTextSource}; use this type when the host has stage / slot
 * structure. A {@link Supplier} is re-read every turn so the product can
 * push a fresh snapshot without rebuilding the room.
 */
public final class RelationSource implements ContextSource {

    static final String HEADER = "[Relation]";

    private final Supplier<RelationSnapshot> snapshots;

    public RelationSource(RelationSnapshot snapshot) {
        RelationSnapshot frozen = snapshot == null ? RelationSnapshot.empty() : snapshot;
        this.snapshots = () -> frozen;
    }

    public RelationSource(Supplier<RelationSnapshot> snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
        RelationSnapshot snapshot = snapshots.get();
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        return List.of(ChatMessage.system(render(snapshot)));
    }

    private static String render(RelationSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        if (!snapshot.stage().isEmpty()) {
            sb.append("stage: ").append(snapshot.stage()).append('\n');
        }
        for (Map.Entry<String, String> slot : snapshot.slots().entrySet()) {
            sb.append(slot.getKey()).append(": ").append(slot.getValue()).append('\n');
        }
        if (!snapshot.note().isEmpty()) {
            sb.append(snapshot.note()).append('\n');
        }
        return sb.toString().trim();
    }
}
