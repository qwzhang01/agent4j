package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.ChatRoom;
import io.github.qwzhang01.agent.chat.context.ExtraTextSource;
import io.github.qwzhang01.agent.chat.context.HistorySource;
import io.github.qwzhang01.agent.chat.context.PersonaSource;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;

/**
 * Room chat demo: 1:1 streaming, then a two-person @mention turn.
 * Zero LLM — fully scripted {@link MockModelClient}.
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.ChatRoomExample
 */
public class ChatRoomExample {

    public static void main(String[] args) {
        System.out.println("=== Java Agent Framework - Chat Room Example ===\n");

        oneOnOne();
        System.out.println();
        groupMention();

        System.out.println("\n=== Done ===");
    }

    private static void oneOnOne() {
        System.out.println("-- 1:1 (no @ needed) --");
        ChatRoom room = ChatRoom.builder()
                .roomId("moonlit:demo:luna")
                .persona(ChatPersona.of("luna", "You are Luna, a quiet cafe regular."))
                .source(new PersonaSource())
                .source(new ExtraTextSource("A rainy afternoon in a small cafe."))
                .source(new HistorySource())
                .modelClient(MockModelClient.scripted().respondText("Hey. Sit anywhere you like."))
                .build();

        System.out.println("User: hi");
        System.out.print("Luna: ");
        room.stream("hi", event -> {
            if (event instanceof AgentEvent.ContentDelta delta) {
                System.out.print(delta.delta());
            } else if (event instanceof AgentEvent.Done) {
                System.out.println();
            }
        });
    }

    private static void groupMention() {
        System.out.println("-- group (must @ someone) --");
        ChatRoom room = ChatRoom.builder()
                .roomId("table:demo")
                .persona(ChatPersona.of("luna", "You are Luna."))
                .persona(ChatPersona.of("bob", "You are Bob."))
                .modelClient(MockModelClient.scripted().respondText("Bob: I heard that."))
                .build();

        String ignored = room.say("hello everyone");
        System.out.println("User: hello everyone");
        System.out.println("Nobody spoke (no @). reply=" + (ignored.isEmpty() ? "<empty>" : ignored));

        System.out.println("User: @bob did you hear?");
        System.out.println("Bob: " + room.say("@bob did you hear?"));
    }
}
