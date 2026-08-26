package io.github.qwzhang01.agent.chat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Opaque memory-scope strings for a room (user / session / pair / channel).
 * <p>
 * The engine does not parse prefixes and does not depend on {@code agent-channel}.
 * Hosts pass the same strings they already use with {@code MemoryStore}.
 *
 * @param scopes ordered, de-duplicated, blank-stripped identifiers
 */
public record RoomIdentity(List<String> scopes) {

    public RoomIdentity {
        scopes = copy(scopes);
    }

    public static RoomIdentity empty() {
        return new RoomIdentity(List.of());
    }

    public static RoomIdentity of(String... scopes) {
        if (scopes == null || scopes.length == 0) {
            return empty();
        }
        return new RoomIdentity(Arrays.asList(scopes));
    }

    public static RoomIdentity of(List<String> scopes) {
        return new RoomIdentity(scopes);
    }

    public boolean isEmpty() {
        return scopes.isEmpty();
    }

    private static List<String> copy(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope != null && !scope.isBlank()) {
                unique.add(scope.trim());
            }
        }
        return unique.isEmpty() ? List.of() : List.copyOf(unique);
    }
}
