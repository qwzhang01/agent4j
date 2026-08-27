package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.guard.ConsistencyGuard;
import io.github.qwzhang01.agent.chat.guard.ConsistencyVerdict;
import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.speaker.MentionSpeaker;
import io.github.qwzhang01.agent.chat.speaker.SoloSpeaker;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistencyGuardTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");
    private static final ChatPersona BOB = ChatPersona.of("bob", "You are Bob.");

    @Test
    void defaultIsNoopAndDoesNotWarn() {
        AtomicInteger warnings = new AtomicInteger();
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(MockModelClient.scripted().respondText("hello"))
                .listener(new ChatListener() {
                    @Override
                    public void onConsistencyWarning(Room room, ChatPersona speaker, String userText,
                                                     String reply, String warning) {
                        warnings.incrementAndGet();
                    }
                })
                .build();

        assertEquals("hello", engine.say("hi"));
        assertEquals(0, warnings.get());
        assertEquals("hello", engine.room().history().get(1).content());
    }

    @Test
    void hostRuleWarnsWithoutRewritingReply() {
        AtomicReference<String> seenAnchor = new AtomicReference<>();
        AtomicReference<String> seenWarning = new AtomicReference<>();
        AtomicInteger replies = new AtomicInteger();

        ConsistencyGuard hostRule = (room, speaker, userText, reply) -> {
            seenAnchor.set(speaker.systemPrompt());
            if (reply.contains("toaster")) {
                return ConsistencyVerdict.warn("drift from " + speaker.personaId());
            }
            return ConsistencyVerdict.ok();
        };

        ChatEngine engine = ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(MockModelClient.scripted().respondText("I am a toaster"))
                .consistencyGuard(hostRule)
                .listener(new ChatListener() {
                    @Override
                    public void onReplied(Room room, ChatPersona speaker, String userText, String reply) {
                        replies.incrementAndGet();
                    }

                    @Override
                    public void onConsistencyWarning(Room room, ChatPersona speaker, String userText,
                                                     String reply, String warning) {
                        seenWarning.set(warning);
                        assertEquals("I am a toaster", reply);
                    }
                })
                .build();

        assertEquals("I am a toaster", engine.say("who are you?"));
        assertEquals("You are Luna.", seenAnchor.get());
        assertEquals("drift from luna", seenWarning.get());
        assertEquals(1, replies.get());
        assertEquals("I am a toaster", engine.room().history().get(1).content());
    }

    @Test
    void okVerdictDoesNotWarn() {
        AtomicInteger warnings = new AtomicInteger();
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(MockModelClient.scripted().respondText("Luna here"))
                .consistencyGuard((room, speaker, userText, reply) -> ConsistencyVerdict.ok())
                .listener(new ChatListener() {
                    @Override
                    public void onConsistencyWarning(Room room, ChatPersona speaker, String userText,
                                                     String reply, String warning) {
                        warnings.incrementAndGet();
                    }
                })
                .build();

        engine.say("hi");
        assertEquals(0, warnings.get());
    }

    @Test
    void guardIsNotCalledOnErrorOrNoSpeaker() {
        AtomicInteger checks = new AtomicInteger();
        ConsistencyGuard counting = (room, speaker, userText, reply) -> {
            checks.incrementAndGet();
            return ConsistencyVerdict.ok();
        };

        ChatEngine failing = ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(MockModelClient.scripted())
                .consistencyGuard(counting)
                .build();
        failing.stream("boom", event -> { });
        assertEquals(0, checks.get());

        ChatEngine silent = ChatEngine.builder()
                .room(new Room("group", List.of(LUNA, BOB)))
                .speakerPolicy(new MentionSpeaker())
                .modelClient(MockModelClient.scripted().respondText("no"))
                .consistencyGuard(counting)
                .build();
        silent.say("hello everyone");
        assertEquals(0, checks.get());
    }

    @Test
    void guardExceptionDoesNotDropReplyOrListener() {
        AtomicInteger replies = new AtomicInteger();
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(MockModelClient.scripted().respondText("kept"))
                .consistencyGuard((room, speaker, userText, reply) -> {
                    throw new IllegalStateException("judge failed");
                })
                .listener(new ChatListener() {
                    @Override
                    public void onReplied(Room room, ChatPersona speaker, String userText, String reply) {
                        replies.incrementAndGet();
                    }
                })
                .build();

        assertEquals("kept", engine.say("hi"));
        assertEquals(1, replies.get());
        assertEquals("kept", engine.room().history().get(1).content());
    }

    @Test
    void chatRoomBuilderPassesGuard() {
        List<String> warnings = new ArrayList<>();
        ChatRoom room = ChatRoom.builder()
                .roomId("solo")
                .persona(LUNA)
                .modelClient(MockModelClient.scripted().respondText("ok"))
                .consistencyGuard((r, speaker, userText, reply) ->
                        ConsistencyVerdict.warn("flagged"))
                .listener(new ChatListener() {
                    @Override
                    public void onConsistencyWarning(Room room, ChatPersona speaker, String userText,
                                                     String reply, String warning) {
                        warnings.add(warning);
                    }
                })
                .build();

        room.say("hi");
        assertEquals(List.of("flagged"), warnings);
    }

    @Test
    void nullGuardIsNoopAndBlankWarnGetsFallback() {
        assertTrue(ConsistencyGuard.noop().check(null, LUNA, "a", "b").consistent());
        assertEquals("inconsistent", ConsistencyVerdict.warn("  ").warning());
        assertEquals("", ConsistencyVerdict.ok().warning());
        assertEquals("", new ConsistencyVerdict(true, "ignored").warning());
    }
}
