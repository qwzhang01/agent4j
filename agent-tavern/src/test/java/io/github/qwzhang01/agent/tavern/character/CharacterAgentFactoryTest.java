package io.github.qwzhang01.agent.tavern.character;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.memory.InMemoryMemoryStore;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.1: card -&gt; Agent translation, verified at the model boundary.
 * <p>
 * The persona claim is only proven where it matters: in the messages the model
 * actually sees (the CapturingModelClient pattern used across Stages 12-15).
 * Also proves the multi-turn continuation primitive the game facade will rely on:
 * {@code agent.run(input, state)} with a held AgentState.
 */
class CharacterAgentFactoryTest {

    private InMemoryMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
    }

    /**
     * Records every ModelRequest the loop sends, answers with a fixed response.
     */
    private static final class CapturingModelClient implements ModelClient {

        private final List<ModelRequest> requests = new ArrayList<>();
        private final ModelResponse next;

        CapturingModelClient(ModelResponse next) {
            this.next = next;
        }

        List<ModelRequest> requests() {
            return requests;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(request);
            return next;
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Done(chat(request)));
        }
    }

    private MemoryEntry episode(String scope, String subject, String content) {
        return new MemoryEntry(
                null, scope, MemoryType.EPISODE, subject, content, 0.8,
                MemoryProvenance.userSaid("player-1", "turn-1", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null
        );
    }

    private CharacterCard card(String id, String name, String persona) {
        return new CharacterCard(id, name, persona, null);
    }

    // ============ Persona Translation ============

    @Test
    @DisplayName("persona lands in the system prompt the model actually sees")
    void personaInjectedIntoSystemPrompt() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("Welcome, traveler."));
        CharacterAgentFactory factory = new CharacterAgentFactory(model, store);

        Agent marcus = factory.create(
                card("marcus", "Marcus", "A warm-hearted barkeep who knows every rumor in town."),
                "game-1", null);
        marcus.run("Hello!");

        assertEquals(1, model.requests().size());
        List<ChatMessage> sent = model.requests().get(0).messages();
        ChatMessage systemMsg = sent.get(0);
        assertEquals(ChatRole.SYSTEM, systemMsg.role());
        assertTrue(systemMsg.content().contains("Marcus"), "identity line names the character");
        assertTrue(systemMsg.content().contains("warm-hearted barkeep"), "persona body is embedded");
        assertTrue(systemMsg.content().contains("Interaction rules"),
                "interaction rules ship from day one so M16.2 tools arrive with their contract");
    }

    @Test
    @DisplayName("same input, two characters, two personas - the executable proof of persona isolation")
    void twoCharactersTwoPersonas() {
        CapturingModelClient marcusModel = new CapturingModelClient(ModelResponse.text("Aye, what'll it be?"));
        CapturingModelClient lyraModel = new CapturingModelClient(ModelResponse.text("Hmph. Make it quick."));

        Agent marcus = new CharacterAgentFactory(marcusModel, store).create(
                card("marcus", "Marcus", "warm-hearted barkeep"), "game-1", null);
        Agent lyra = new CharacterAgentFactory(lyraModel, store).create(
                card("lyra", "Lyra", "sharp-tongued bard who despises small talk"), "game-1", null);

        marcus.run("What's the mood tonight?");
        lyra.run("What's the mood tonight?");

        String marcusPrompt = marcusModel.requests().get(0).messages().get(0).content();
        String lyraPrompt = lyraModel.requests().get(0).messages().get(0).content();
        assertTrue(marcusPrompt.contains("warm-hearted barkeep"));
        assertTrue(lyraPrompt.contains("sharp-tongued bard"));
        assertNotEquals(marcusPrompt, lyraPrompt,
                "two characters answering the same line must carry different personas");
    }

    // ============ Config Bound ============

    @Test
    @DisplayName("agent name = characterId; maxSteps bound is configurable with a tight default")
    void configBoundToCard() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("ok"));
        CharacterAgentFactory defaultFactory = new CharacterAgentFactory(model, store);
        CharacterAgentFactory tightFactory = new CharacterAgentFactory(
                model, new CharacterMemory(store), 3);

        Agent defaultAgent = defaultFactory.create(card("marcus", "Marcus", "p"), "game-1", null);
        Agent tightAgent = tightFactory.create(card("marcus", "Marcus", "p"), "game-1", null);

        assertEquals("marcus", defaultAgent.getConfig().getName());
        assertEquals(CharacterAgentFactory.DEFAULT_MAX_STEPS, defaultAgent.getConfig().getMaxSteps());
        assertEquals(3, tightAgent.getConfig().getMaxSteps());
    }

    @Test
    @DisplayName("null gameTools falls back to an empty registry - plain conversation works")
    void nullToolsPlainConversation() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("Just talk."));
        CharacterAgentFactory factory = new CharacterAgentFactory(model, store);

        Agent marcus = factory.create(card("marcus", "Marcus", "p"), "game-1", null);
        String reply = marcus.run("Tell me about the tavern.");

        assertEquals("Just talk.", reply);
        assertTrue(marcus.getConfig().getToolRegistry() != null);
    }

    // ============ Memory Through the Full Agent Path ============

    @Test
    @DisplayName("end-to-end: whitelist memories reach the model, foreign ones never do")
    void memoryInjectedThroughAgentRun() {
        store.write(episode("agent:marcus", "mead", "The player bought me a mead last visit"));
        store.write(episode("agent:lyra", "secret", "I secretly dislike loud crowds"));
        store.write(episode("session:game-2", "brawl", "A stranger argued with the bard in game two"));

        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("Good to see you again."));
        CharacterAgentFactory factory = new CharacterAgentFactory(model, store);
        Agent marcus = factory.create(card("marcus", "Marcus", "warm-hearted barkeep"), "game-1", null);

        marcus.run("Hello again!");

        // message #1 is the [Known memories] injection right after the system prompt
        String memories = model.requests().get(0).messages().get(1).content();
        assertTrue(memories.contains("[Known memories]"));
        assertTrue(memories.contains("mead"), "cross-game character memory is visible");
        assertFalse(memories.contains("loud crowds"), "another character's memory is invisible");
        assertFalse(memories.contains("game two"), "another game's plot is invisible");
    }

    // ============ Multi-Turn Continuation (game facade primitive) ============

    @Test
    @DisplayName("a held AgentState carries the conversation across turns (M16.5 facade primitive)")
    void agentStateContinuesAcrossTurns() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("Sure."));
        CharacterAgentFactory factory = new CharacterAgentFactory(model, store);
        Agent marcus = factory.create(card("marcus", "Marcus", "p"), "game-1", null);

        AgentState state = new AgentState();
        marcus.run("First", state);
        marcus.run("Second", state);

        // turn 1 request: [SYSTEM, USER(First)]
        assertEquals(2, model.requests().get(0).messages().size());
        // turn 2 request: [SYSTEM, USER(First), ASSISTANT(Sure.), USER(Second)]
        List<ChatMessage> second = model.requests().get(1).messages();
        assertEquals(4, second.size());
        assertEquals("First", second.get(1).content());
        assertEquals(ChatRole.ASSISTANT, second.get(2).role());
        assertEquals("Second", second.get(3).content());
    }
}
