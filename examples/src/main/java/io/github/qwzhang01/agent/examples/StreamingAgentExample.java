package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;

/**
 * Minimal streaming demo: print content deltas as they arrive.
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.StreamingAgentExample
 */
public class StreamingAgentExample {

    public static void main(String[] args) {
        System.out.println("=== Java Agent Framework - Streaming Agent Example ===\n");

        MockModelClient modelClient = MockModelClient.scripted()
                .respondText("Hello from a streaming mock.");

        AgentConfig config = new AgentConfig(
                "stream-demo",
                "You are a helpful assistant.",
                modelClient,
                null,
                5
        );
        Agent agent = new SimpleAgent(config);

        String userInput = "Hi";
        System.out.println("User: " + userInput);
        System.out.print("Agent: ");

        agent.stream(userInput, event -> {
            if (event instanceof AgentEvent.ContentDelta delta) {
                System.out.print(delta.delta());
            } else if (event instanceof AgentEvent.Done done) {
                System.out.println();
                System.out.println("Done. status=" + done.state().getStatus());
            } else if (event instanceof AgentEvent.Error err) {
                System.out.println();
                System.out.println("Error: " + err.message());
            }
        });

        System.out.println("\n=== Done ===");
    }
}
