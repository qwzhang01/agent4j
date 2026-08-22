package io.github.qwzhang01.agent.channel.identity;

import java.util.Set;

/**
 * Supplies the invoking user's role capabilities within a channel
 * (Stage 12 D4).
 * <p>
 * This is the "user side" input of the permission intersection. The
 * assembly layer backs it with real channel-membership and role data
 * (e.g. ChannelContext in M12.2); tests back it with a map.
 * <p>
 * Contract:
 * <ul>
 *   <li>return {@code null} - the user is not a member of the channel</li>
 *   <li>return a set (possibly empty) - the user is a member with those
 *       role capabilities; an empty set still intersects to empty and the
 *       resolution fails closed with a different, more precise reason</li>
 * </ul>
 */
@FunctionalInterface
public interface ChannelRolePermissions {

    /**
     * Role capabilities of {@code userId} in {@code channelId}, or null if
     * not a member.
     */
    Set<String> capabilities(String channelId, String userId);
}
