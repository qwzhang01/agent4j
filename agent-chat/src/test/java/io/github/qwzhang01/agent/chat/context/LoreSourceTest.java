package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.ChatRoom;
import io.github.qwzhang01.agent.chat.RecordingModelClient;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.chat.RoomMessage;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreSourceTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");

    @Test
    void missContributesNothing() {
        LoreSource source = new LoreSource(entry("harbor", "The harbor is under curfew."));
        assertTrue(source.contribute(room(), LUNA, "how's the weather").isEmpty());
    }

    @Test
    void keywordHitIsCaseInsensitive() {
        LoreSource source = new LoreSource(entry("Harbor", "The harbor is under curfew."));
        String text = source.contribute(room(), LUNA, "walk to the HARBOR").get(0).content();
        assertTrue(text.startsWith("[Lore]\n"));
        assertTrue(text.contains("The harbor is under curfew."));
    }

    @Test
    void chineseKeywordIsASubstring() {
        LoreSource source = new LoreSource(entry("港口", "港口宵禁。"));
        String text = source.contribute(room(), LUNA, "今晚去港口看看").get(0).content();
        assertTrue(text.contains("港口宵禁。"));
    }

    @Test
    void regexUsesHostCompiledPattern() {
        LoreSource source = new LoreSource(LoreEntry.of(
                "Fog horns mark the hour.",
                LoreTrigger.regex(Pattern.compile("fog\\s+horn", Pattern.CASE_INSENSITIVE))));
        assertFalse(source.contribute(room(), LUNA, "hear the FOG HORN?").isEmpty());
        assertTrue(source.contribute(room(), LUNA, "just fog").isEmpty());
    }

    @Test
    void onlyMatchingEntriesInjectInRegistrationOrder() {
        LoreSource source = new LoreSource(List.of(
                entry("harbor", "first harbor"),
                entry("lantern", "lanterns stay lit"),
                entry("pier", "second pier")));
        String text = source.contribute(room(), LUNA, "the pier by the harbor").get(0).content();
        assertTrue(text.contains("first harbor"));
        assertTrue(text.contains("second pier"));
        assertFalse(text.contains("lanterns stay lit"));
        assertTrue(text.indexOf("first harbor") < text.indexOf("second pier"));
    }

    @Test
    void limitKeepsEarlierHits() {
        LoreSource source = new LoreSource(List.of(
                entry("harbor", "one"),
                entry("harbor", "two"),
                entry("harbor", "three")), 2);
        String text = source.contribute(room(), LUNA, "harbor").get(0).content();
        assertTrue(text.contains("one"));
        assertTrue(text.contains("two"));
        assertFalse(text.contains("three"));
    }

    @Test
    void blankContentIsSkippedEvenOnHit() {
        LoreSource source = new LoreSource(
                LoreEntry.of("  ", LoreTrigger.keywords("harbor")),
                entry("harbor", "kept"));
        String text = source.contribute(room(), LUNA, "harbor").get(0).content();
        assertTrue(text.contains("kept"));
        assertEquals(1, text.lines().skip(1).count());
    }

    @Test
    void blankUserTextDoesNotFireKeywords() {
        LoreSource source = new LoreSource(entry("harbor", "The harbor is under curfew."));
        assertTrue(source.contribute(room(), LUNA, "  ").isEmpty());
        assertTrue(source.contribute(room(), LUNA, null).isEmpty());
    }

    @Test
    void historyIsNotScanned() {
        Room room = room();
        room.append(RoomMessage.user("we walked to the harbor"));
        room.append(RoomMessage.assistant("luna", "yes"));
        LoreSource source = new LoreSource(entry("harbor", "The harbor is under curfew."));
        assertTrue(source.contribute(room, LUNA, "what now?").isEmpty());
    }

    @Test
    void keywordOrRegexEitherSideHits() {
        LoreEntry mixed = LoreEntry.of(
                "mixed",
                LoreTrigger.of(List.of("harbor"), Pattern.compile("pier-\\d+")));
        LoreSource source = new LoreSource(mixed);
        assertFalse(source.contribute(room(), LUNA, "the harbor").isEmpty());
        assertFalse(source.contribute(room(), LUNA, "see pier-7").isEmpty());
        assertTrue(source.contribute(room(), LUNA, "nothing here").isEmpty());
    }

    @Test
    void emptyTriggerRejected() {
        assertThrows(IllegalArgumentException.class, () -> LoreTrigger.keywords(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> LoreTrigger.keywords("  "));
        assertThrows(IllegalArgumentException.class, () -> LoreTrigger.regex("  "));
        assertThrows(IllegalArgumentException.class, () -> LoreTrigger.regex((Pattern) null));
        assertThrows(IllegalArgumentException.class, () -> LoreTrigger.of(List.of(), null));
    }

    @Test
    void chatRoomRequestContainsLoreOnHitOnly() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("a").respondText("b"));
        ChatRoom chat = ChatRoom.builder()
                .roomId("solo")
                .persona(LUNA)
                .source(new PersonaSource())
                .source(new LoreSource(entry("harbor", "The harbor is under curfew.")))
                .source(new HistorySource())
                .modelClient(model)
                .build();

        chat.say("how's the weather");
        assertFalse(joined(model.requests.get(0).messages()).contains("[Lore]"));

        chat.say("walk to the harbor");
        String second = joined(model.requests.get(1).messages());
        assertTrue(second.contains("[Lore]"));
        assertTrue(second.contains("The harbor is under curfew."));
        assertTrue(second.contains("walk to the harbor"));
    }

    @Test
    void assemblerDefaultsDoNotMountLoreSource() {
        assertTrue(ContextAssembler.defaults().sources().stream()
                .noneMatch(source -> source instanceof LoreSource));
    }

    private static Room room() {
        return new Room("r", List.of(LUNA));
    }

    private static LoreEntry entry(String keyword, String content) {
        return LoreEntry.of(content, LoreTrigger.keywords(keyword));
    }

    private static String joined(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append(message.content()).append('\n');
        }
        return sb.toString();
    }
}
