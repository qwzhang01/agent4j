package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.context.ContextAssembler;
import io.github.qwzhang01.agent.chat.context.ExtraTextSource;
import io.github.qwzhang01.agent.chat.context.HistorySource;
import io.github.qwzhang01.agent.chat.context.PersonaSource;
import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.speaker.MentionSpeaker;
import io.github.qwzhang01.agent.chat.speaker.RoundRobinSpeaker;
import io.github.qwzhang01.agent.chat.speaker.SoloSpeaker;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatEngineTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");
    private static final ChatPersona BOB = ChatPersona.of("bob", "You are Bob.");

    @Test
    void soloStreamsWithoutMention() {
        RecordingModelClient model = recording(MockModelClient.scripted().respondText("hello there"));
        ChatEngine engine = soloEngine(model);
        List<AgentEvent> events = new ArrayList<>();

        engine.stream("hi", events::add);

        assertEquals(2, events.size());
        assertEquals(new AgentEvent.ContentDelta("hello there"), events.get(0));
        AgentEvent.Done done = assertInstanceOf(AgentEvent.Done.class, events.get(1));
        assertEquals("hello there", done.finalAnswer());
        assertEquals(2, engine.room().history().size());
        assertEquals("luna", engine.room().history().get(1).speakerId());
    }

    @Test
    void sayReturnsTheReply() {
        ChatEngine engine = soloEngine(MockModelClient.scripted().respondText("pong"));
        assertEquals("pong", engine.say("ping"));
    }

    @Test
    void secondTurnSeesFirstRoundAndExtraText() {
        RecordingModelClient model = recording(
                MockModelClient.scripted().respondText("first-reply").respondText("second-reply"));
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("cafe", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .assembler(new ContextAssembler(List.of(
                        new PersonaSource(),
                        new ExtraTextSource("rainy cafe"),
                        new HistorySource())))
                .modelClient(model)
                .build();

        engine.say("hello");
        engine.say("remember me?");

        assertEquals(2, model.requests.size());
        List<ChatMessage> second = model.requests.get(1).messages();
        assertEquals("You are Luna.", second.get(0).content());
        assertEquals(ChatRole.SYSTEM, second.get(0).role());
        assertEquals("rainy cafe", second.get(1).content());
        assertEquals("hello", second.get(2).content());
        assertEquals(ChatRole.USER, second.get(2).role());
        assertEquals("first-reply", second.get(3).content());
        assertEquals(ChatRole.ASSISTANT, second.get(3).role());
        assertEquals("remember me?", second.get(4).content());
        assertEquals(ChatRole.USER, second.get(4).role());
    }

    @Test
    void firstTurnDoesNotDuplicateTheUserLine() {
        RecordingModelClient model = recording(MockModelClient.scripted().respondText("ok"));
        ChatEngine engine = soloEngine(model);
        engine.say("only once");

        List<String> userLines = model.requests.get(0).messages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .map(ChatMessage::content)
                .toList();
        assertEquals(List.of("only once"), userLines);
    }

    @Test
    void mentionPicksOnlyTheNamedSpeaker() {
        RecordingModelClient model = recording(MockModelClient.scripted().respondText("bob here"));
        ChatEngine engine = groupEngine(model, new MentionSpeaker());

        assertEquals("bob here", engine.say("hey @bob"));
        assertEquals("bob", engine.room().history().get(1).speakerId());
        String system = model.requests.get(0).messages().get(0).content();
        assertEquals("You are Bob.", system);
    }

    @Test
    void noMentionWithoutFallbackCallsOnNoSpeaker() {
        AtomicInteger noSpeaker = new AtomicInteger();
        AtomicInteger replied = new AtomicInteger();
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("group", List.of(LUNA, BOB)))
                .speakerPolicy(new MentionSpeaker())
                .modelClient(MockModelClient.scripted().respondText("should not run"))
                .listener(new ChatListener() {
                    @Override
                    public void onReplied(Room room, ChatPersona speaker, String userText, String reply) {
                        replied.incrementAndGet();
                    }

                    @Override
                    public void onNoSpeaker(Room room, String userText) {
                        noSpeaker.incrementAndGet();
                    }
                })
                .build();

        List<AgentEvent> events = new ArrayList<>();
        engine.stream("hello everyone", events::add);

        assertTrue(events.isEmpty());
        assertEquals(1, noSpeaker.get());
        assertEquals(0, replied.get());
        assertEquals(1, engine.room().history().size());
        assertEquals("hello everyone", engine.room().history().get(0).content());
        assertEquals("", engine.say("still no one"));
        assertEquals(2, noSpeaker.get());
    }

    @Test
    void roundRobinRotatesSpeakersWithoutMention() {
        RecordingModelClient model = recording(
                MockModelClient.scripted()
                        .respondText("a1")
                        .respondText("b1")
                        .respondText("a2"));
        ChatEngine engine = groupEngine(model, new RoundRobinSpeaker());

        assertEquals("a1", engine.say("one"));
        assertEquals("luna", engine.room().history().get(1).speakerId());

        assertEquals("b1", engine.say("two"));
        assertEquals("bob", engine.room().history().get(3).speakerId());

        assertEquals("a2", engine.say("three"));
        assertEquals("luna", engine.room().history().get(5).speakerId());
        assertEquals("You are Luna.", model.requests.get(2).messages().get(0).content());
    }

    @Test
    void onRepliedFiresOnceAfterSuccess() {
        AtomicInteger replies = new AtomicInteger();
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(MockModelClient.scripted().respondText("hi"))
                .listener(new ChatListener() {
                    @Override
                    public void onReplied(Room room, ChatPersona speaker, String userText, String reply) {
                        replies.incrementAndGet();
                        assertEquals("luna", speaker.personaId());
                        assertEquals("yo", userText);
                        assertEquals("hi", reply);
                    }
                })
                .build();

        engine.say("yo");
        assertEquals(1, replies.get());
    }

    @Test
    void modelErrorKeepsUserLineAndSkipsAssistant() {
        AtomicInteger errors = new AtomicInteger();
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(MockModelClient.scripted())
                .listener(new ChatListener() {
                    @Override
                    public void onError(Room room, ChatPersona speaker, String userText,
                                        String message, Throwable cause) {
                        errors.incrementAndGet();
                    }
                })
                .build();

        List<AgentEvent> events = new ArrayList<>();
        engine.stream("boom", events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Error));
        assertEquals(1, errors.get());
        assertEquals(1, engine.room().history().size());
        assertEquals("user", engine.room().history().get(0).speakerId());
    }

    @Test
    void listenerExceptionDoesNotBlockHistoryOrOthers() {
        AtomicInteger second = new AtomicInteger();
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(MockModelClient.scripted().respondText("ok"))
                .listener(new ChatListener() {
                    @Override
                    public void onReplied(Room room, ChatPersona speaker, String userText, String reply) {
                        throw new IllegalStateException("host blew up");
                    }
                })
                .listener(new ChatListener() {
                    @Override
                    public void onReplied(Room room, ChatPersona speaker, String userText, String reply) {
                        second.incrementAndGet();
                    }
                })
                .build();

        assertEquals("ok", engine.say("hi"));
        assertEquals(1, second.get());
        assertEquals(2, engine.room().history().size());
    }

    @Test
    void nullUserTextRejected() {
        ChatEngine engine = soloEngine(MockModelClient.scripted().respondText("x"));
        assertThrows(IllegalArgumentException.class, () -> engine.say(null));
    }

    private static ChatEngine soloEngine(io.github.qwzhang01.agent.core.client.ModelClient model) {
        return ChatEngine.builder()
                .room(new Room("solo", List.of(LUNA)))
                .speakerPolicy(new SoloSpeaker())
                .modelClient(model)
                .build();
    }

    private static ChatEngine groupEngine(io.github.qwzhang01.agent.core.client.ModelClient model,
                                          io.github.qwzhang01.agent.chat.speaker.SpeakerPolicy policy) {
        return ChatEngine.builder()
                .room(new Room("group", List.of(LUNA, BOB)))
                .speakerPolicy(policy)
                .modelClient(model)
                .build();
    }

    private static RecordingModelClient recording(MockModelClient mock) {
        return new RecordingModelClient(mock);
    }
}
