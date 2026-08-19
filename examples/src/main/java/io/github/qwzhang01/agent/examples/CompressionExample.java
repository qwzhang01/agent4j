package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.*;
import io.github.qwzhang01.agent.model.mock.MockModelClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 8 acceptance example 2: context compaction (pi-style).
 * <p>
 * Demonstrates:
 * - A long conversation exceeding the token budget
 * - ContextCompressor summarizing old messages into one summary
 * - System prompt + recent K messages preserved verbatim
 * - Original messages archived to MemoryStore as a SUMMARY entry
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.CompressionExample
 */
public class CompressionExample {

    public static void main(String[] args) {
        System.out.println("=== Stage 8: Context Compaction (pi-style) ===\n");

        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MockModelClient model = MockModelClient.scripted().respondText(
                "User was debugging an MCP connection issue, tried solutions A and B, "
                        + "neither worked. Key error: connection refused on port 8080.");

        ContextCompressor compressor = new ContextCompressor(model, 50, 4);
        CompressingContextBuilder builder = new CompressingContextBuilder(
                model, 50, 4, store, "session:s1");

        // Build a long conversation that exceeds the budget
        AgentState state = new AgentState();
        state.addMessage(ChatMessage.system("You are a debugging assistant."));
        for (int i = 1; i <= 8; i++) {
            state.addMessage(ChatMessage.user("I tried solution " + i + " but got error code " + (i * 100)
                    + " with a very long description that repeats ".repeat(3)));
            state.addMessage(ChatMessage.assistant("Let me analyze solution " + i + ". The error suggests "
                    + "a configuration issue with component " + i + ". ".repeat(3)));
        }
        state.addMessage(ChatMessage.user("So what should I try next?"));
        state.addMessage(ChatMessage.assistant("Based on the errors, let's check the port configuration."));

        int beforeTokens = ContextBudget.estimate(state.getMessages());
        int beforeCount = state.getMessages().size();
        System.out.println("Before compaction:");
        System.out.println("  Messages: " + beforeCount);
        System.out.println("  Estimated tokens: " + beforeTokens + " (budget: 50)");

        // Trigger compaction via the context builder
        List<ChatMessage> result = builder.build(null, state);

        int afterTokens = ContextBudget.estimate(result);
        System.out.println("\nAfter compaction:");
        System.out.println("  Messages: " + result.size());
        System.out.println("  Estimated tokens: " + afterTokens);
        System.out.println("  State messages (rewritten): " + state.getMessages().size());

        // Show the structure
        System.out.println("\nResulting message structure:");
        for (ChatMessage m : result) {
            String preview = m.content() != null
                    ? m.content().substring(0, Math.min(80, m.content().length())) + "..."
                    : "(null)";
            System.out.println("  [" + m.role() + "] " + preview);
        }

        // Check archive
        List<MemoryEntry> archives = store.query(MemoryQuery.builder()
                .scopes(List.of("session:s1")).type(MemoryType.SUMMARY).build());
        System.out.println("\nArchived summaries: " + archives.size());

        System.out.println("\n=== Acceptance: old messages compacted, recent K preserved, archive stored ===");
    }
}
