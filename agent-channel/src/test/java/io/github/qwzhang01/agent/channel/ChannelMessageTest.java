package io.github.qwzhang01.agent.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChannelMessage} and {@link ChannelContext}
 * (Stage 12 M12.2).
 */
class ChannelMessageTest {

    // ============ ChannelContext ============

    @Test
    @DisplayName("ChannelContext: membership check + defensive copy")
    void context_membershipAndCopy() {
        java.util.Set<String> source = new java.util.HashSet<>(java.util.Set.of("alice"));
        ChannelContext channel = new ChannelContext("team-eng", source);

        source.add("mallory");   // mutate the source after construction

        assertTrue(channel.isMember("alice"));
        assertFalse(channel.isMember("mallory"), "source mutation must not leak in");
        assertFalse(channel.isMember(null));
        assertEquals(java.util.Set.of("alice"), channel.members());
    }

    @Test
    @DisplayName("ChannelContext: blank channelId rejected")
    void context_blankIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ChannelContext.of(" ", "alice"));
    }

    // ============ Factories ============

    @Test
    @DisplayName("of/mention factories set the mention flag and a timestamp")
    void factories_flagsAndTimestamp() {
        ChannelMessage plain = ChannelMessage.of("team-eng", "alice", "lunch?");
        assertFalse(plain.mentionsAgent());
        assertNotNull(plain.timestamp());

        ChannelMessage mention = ChannelMessage.mention("team-eng", "alice", "hi bot");
        assertTrue(mention.mentionsAgent());
        assertNotNull(mention.timestamp());
    }

    @Test
    @DisplayName("autoDetect: @agentId prefix flags the mention, anything else stays plain")
    void autoDetect_prefixDetection() {
        ChannelMessage m1 = ChannelMessage.autoDetect("team-eng", "alice", "@eng-bot 你好", "eng-bot");
        assertTrue(m1.mentionsAgent());

        ChannelMessage m2 = ChannelMessage.autoDetect("team-eng", "alice", "你好 eng-bot", "eng-bot");
        assertFalse(m2.mentionsAgent(), "mention must be a PREFIX, not a substring");

        ChannelMessage m3 = ChannelMessage.autoDetect("team-eng", "alice", "@eng-bots are cool", "eng-bot");
        assertFalse(m3.mentionsAgent(), "longer agent id must not half-match");
    }

    // ============ Mention stripping ============

    @Test
    @DisplayName("textWithoutMention strips the @prefix and one separator")
    void stripPrefix_separators() {
        String agentId = "eng-bot";

        assertEquals("你好",
                ChannelMessage.mention("c", "a", "@eng-bot 你好").textWithoutMention(agentId));
        assertEquals("check CI",
                ChannelMessage.mention("c", "a", "@eng-bot: check CI").textWithoutMention(agentId));
        assertEquals("全角逗号也剥",
                ChannelMessage.mention("c", "a", "@eng-bot，全角逗号也剥").textWithoutMention(agentId));
        assertEquals("no separator kept as-is",
                ChannelMessage.mention("c", "a", "@eng-botno separator kept as-is").textWithoutMention(agentId));
    }

    @Test
    @DisplayName("textWithoutMention returns raw text when there is no matching prefix")
    void stripPrefix_noMatch() {
        assertEquals("plain text",
                ChannelMessage.of("c", "a", "plain text").textWithoutMention("eng-bot"));
        assertEquals("@other-bot hello",
                ChannelMessage.mention("c", "a", "@other-bot hello").textWithoutMention("eng-bot"),
                "a mention of ANOTHER agent is not ours to strip");
    }

    @Test
    @DisplayName("null text / null timestamp defaults are handled")
    void validation_defaults() {
        assertThrows(NullPointerException.class,
                () -> ChannelMessage.of("c", null, "text"));
        // timestamp defaults to now when null slips through the canonical constructor
        ChannelMessage m = new ChannelMessage("c", "a", "text", false, null);
        assertNotNull(m.timestamp());
        assertTrue(m.timestamp().isBefore(Instant.now().plusSeconds(1)));
    }
}
