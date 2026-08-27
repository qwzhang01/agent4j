package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.ChatRoom;
import io.github.qwzhang01.agent.chat.RecordingModelClient;
import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.RelationSnapshot;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationSourceTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");

    @Test
    void emptySnapshotContributesNothing() {
        Room room = new Room("r", List.of(LUNA));
        assertTrue(new RelationSource(RelationSnapshot.empty())
                .contribute(room, LUNA, "hi").isEmpty());
        assertTrue(new RelationSource((RelationSnapshot) null)
                .contribute(room, LUNA, "hi").isEmpty());
    }

    @Test
    void noteOnlyIsInjectedVerbatim() {
        String text = new RelationSource(RelationSnapshot.note("already talked a few times"))
                .contribute(new Room("r", List.of(LUNA)), LUNA, "hi")
                .get(0).content();
        assertTrue(text.startsWith("[Relation]\n"));
        assertTrue(text.contains("already talked a few times"));
        assertFalse(text.contains("stage:"));
    }

    @Test
    void stageAndSlotsAreInjectedWithoutScoring() {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("trust", "3");
        slots.put("heat", "warm");
        RelationSnapshot snapshot = RelationSnapshot.of("acquainted", slots, "keep the pace");

        String text = new RelationSource(snapshot)
                .contribute(new Room("r", List.of(LUNA)), LUNA, "hi")
                .get(0).content();

        assertTrue(text.contains("stage: acquainted"));
        assertTrue(text.contains("trust: 3"));
        assertTrue(text.contains("heat: warm"));
        assertTrue(text.contains("keep the pace"));
        assertFalse(text.contains("trust: 4"));
        assertTrue(text.indexOf("trust: 3") < text.indexOf("heat: warm"));
    }

    @Test
    void blankSlotsAndStageAreDropped() {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put(" ", "ignored");
        slots.put("trust", null);
        slots.put("heat", "warm");
        RelationSnapshot snapshot = RelationSnapshot.of("  ", slots, "  ");
        String text = new RelationSource(snapshot)
                .contribute(new Room("r", List.of(LUNA)), LUNA, "hi")
                .get(0).content();
        assertEquals("[Relation]\nheat: warm", text);
    }

    @Test
    void supplierIsRereadEveryTurn() {
        AtomicReference<RelationSnapshot> latest = new AtomicReference<>(
                RelationSnapshot.of("new", Map.of("trust", "1")));
        RelationSource source = new RelationSource(latest::get);
        Room room = new Room("r", List.of(LUNA));

        assertTrue(source.contribute(room, LUNA, "first").get(0).content().contains("trust: 1"));

        latest.set(RelationSnapshot.of("new", Map.of("trust", "2")));
        String second = source.contribute(room, LUNA, "second").get(0).content();
        assertTrue(second.contains("trust: 2"));
        assertFalse(second.contains("trust: 1"));
    }

    @Test
    void frozenSnapshotDoesNotAdvanceOnItsOwn() {
        RelationSource source = new RelationSource(
                RelationSnapshot.of("new", Map.of("trust", "3")));
        Room room = new Room("r", List.of(LUNA));
        String first = source.contribute(room, LUNA, "a").get(0).content();
        String second = source.contribute(room, LUNA, "b").get(0).content();
        assertEquals(first, second);
        assertTrue(second.contains("trust: 3"));
    }

    @Test
    void chatRoomRequestContainsRelationSlice() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        ChatRoom chat = ChatRoom.builder()
                .roomId("solo")
                .persona(LUNA)
                .source(new PersonaSource())
                .source(new RelationSource(RelationSnapshot.of("acquainted", Map.of("trust", "3"))))
                .source(new HistorySource())
                .modelClient(model)
                .build();

        chat.say("hello");
        String request = joined(model.requests.get(0).messages());
        assertTrue(request.contains("[Relation]"));
        assertTrue(request.contains("stage: acquainted"));
        assertTrue(request.contains("trust: 3"));
        assertTrue(request.contains("hello"));
    }

    @Test
    void assemblerDefaultsDoNotMountRelationSource() {
        assertTrue(ContextAssembler.defaults().sources().stream()
                .noneMatch(source -> source instanceof RelationSource));
    }

    private static String joined(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append(message.content()).append('\n');
        }
        return sb.toString();
    }
}
