package io.github.qwzhang01.agent.channel;

import java.util.Objects;
import java.util.Set;

/**
 * Channel metadata: the "where" of the identity triple (Stage 12 M12.2).
 * <p>
 * The member list here is the single source of truth for membership:
 * {@code SharedAgentSession} combines it with role permissions so that a non-member is denied before any role lookup happens
 * (USER_NOT_IN_CHANNEL), while a member without roles gets the more
 * precise EMPTY_PERMISSION_INTERSECTION denial.
 * <p>
 * v1: immutable record - membership changes rebuild the context. Dynamic
 * membership with listener notifications is v2 scope.
 *
 * @param channelId stable channel identifier, e.g. "team-eng"
 * @param members   channel member user ids
 */
public record ChannelContext(String channelId, Set<String> members) {

    public ChannelContext {
        Objects.requireNonNull(channelId, "channelId must not be null");
        Objects.requireNonNull(members, "members must not be null");
        if (channelId.isBlank()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        members = Set.copyOf(members);
    }

    // ============ Factory Methods ============

    /**
     * A channel with the given members.
     */
    public static ChannelContext of(String channelId, String... members) {
        return new ChannelContext(channelId, Set.of(members));
    }

    // ============ Predicates ============

    /**
     * Whether the user is a member of this channel.
     */
    public boolean isMember(String userId) {
        return userId != null && members.contains(userId);
    }
}
