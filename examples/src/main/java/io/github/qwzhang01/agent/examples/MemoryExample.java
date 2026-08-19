package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.*;
import io.github.qwzhang01.agent.model.mock.MockModelClient;

import java.time.Instant;
import java.util.List;

/**
 * Stage 8 acceptance example 1: multi-turn personal memory loop.
 * <p>
 * Demonstrates:
 * - Turn 1: user states a preference -> MemoryExtractor stores it
 * - Turn 2: user asks a related question -> MemoryContextBuilder injects the stored memory
 * - The model's request in turn 2 contains the preference from turn 1
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.MemoryExample
 */
public class MemoryExample {

    public static void main(String[] args) {
        System.out.println("=== Stage 8: Multi-Turn Personal Memory ===\n");

        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryRetriever retriever = new MemoryRetriever(store);
        MemoryExtractor extractor = new MemoryExtractor();
        MemoryPolicy policy = new MemoryPolicy(0.5);
        MemoryContextBuilder ctxBuilder = new MemoryContextBuilder(
                retriever, List.of("user:u1"), null, null, null, 0);

        MockModelClient model = MockModelClient.ruleBased();
        ChatSession session = new ChatSession("s1");

        // ---- Turn 1: user states a preference ----
        System.out.println("--- Turn 1: User states a preference ---");
        session.addUser("记住我喜欢深色模式");
        AgentState state1 = session.toAgentState("You are a helpful assistant.");
        System.out.println("User: 记住我喜欢深色模式");

        List<ChatMessage> ctx1 = ctxBuilder.build(null, state1);
        System.out.println("(context has " + ctx1.size() + " messages, no memories yet)");

        // Simulate model response
        state1.addMessage(ChatMessage.assistant("好的，我记住了你喜欢深色模式。"));
        System.out.println("Assistant: 好的，我记住了你喜欢深色模式。\n");

        // Extract & store memories from turn 1
        int stored = extractor.extractAndStore(state1.getMessages(), "user:u1",
                MemoryProvenance.userSaid("u1", "run-1", Instant.now()), policy, store);
        System.out.println("Extracted & stored " + stored + " memory entries.\n");

        session.syncFrom(state1);

        // ---- Turn 2: user asks a question ----
        System.out.println("--- Turn 2: User asks a related question ---");
        session.addUser("帮我设置界面");
        AgentState state2 = session.toAgentState("You are a helpful assistant.");
        System.out.println("User: 帮我设置界面");

        List<ChatMessage> ctx2 = ctxBuilder.build(null, state2);
        System.out.println("(context has " + ctx2.size() + " messages)");

        // Show that the memory was injected
        boolean memoryInjected = ctx2.stream()
                .anyMatch(m -> m.content() != null && m.content().contains("深色模式")
                        && m.content().contains("Known memories"));
        System.out.println("Memory injected into turn 2 context: " + memoryInjected);

        // Print the memory block
        ctx2.stream()
                .filter(m -> m.content() != null && m.content().contains("Known memories"))
                .forEach(m -> System.out.println("Injected memory block:\n" + m.content()));

        System.out.println("\n=== Acceptance: preference from turn 1 is recalled in turn 2 ===");
    }
}
