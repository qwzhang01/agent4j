package io.github.qwzhang01.agent.chat.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One conversation room. Members are the AI personas; history is the
 * shared transcript. No turn counter, location, or world flags.
 * Optional {@link RoomIdentity} holds opaque memory-scope strings.
 */
public final class Room {

    private final String roomId;
    private final RoomIdentity identity;
    private final Map<String, ChatPersona> members = new LinkedHashMap<>();
    private final List<RoomMessage> history = new ArrayList<>();

    public Room(String roomId, List<ChatPersona> members) {
        this(roomId, members, RoomIdentity.empty());
    }

    public Room(String roomId, List<ChatPersona> members, RoomIdentity identity) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be null or blank");
        }
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("a room needs at least one persona");
        }
        this.roomId = roomId;
        this.identity = identity == null ? RoomIdentity.empty() : identity;
        for (ChatPersona persona : members) {
            Objects.requireNonNull(persona, "persona");
            if (this.members.put(persona.personaId(), persona) != null) {
                throw new IllegalArgumentException("duplicate personaId: " + persona.personaId());
            }
        }
    }

    public String roomId() {
        return roomId;
    }

    public RoomIdentity identity() {
        return identity;
    }

    /**
     * Opaque scopes from {@link #identity()}; empty when the host set none.
     */
    public List<String> scopes() {
        return identity.scopes();
    }

    public List<ChatPersona> members() {
        return List.copyOf(members.values());
    }

    public Optional<ChatPersona> member(String personaId) {
        return Optional.ofNullable(members.get(personaId));
    }

    public List<RoomMessage> history() {
        return List.copyOf(history);
    }

    public void append(RoomMessage message) {
        history.add(Objects.requireNonNull(message, "message"));
    }
}
