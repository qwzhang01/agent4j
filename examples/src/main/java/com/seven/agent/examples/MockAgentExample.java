package com.seven.agent.examples;

import com.seven.agent.core.agent.Agent;
import com.seven.agent.core.agent.AgentConfig;
import com.seven.agent.core.agent.SimpleAgent;
import com.seven.agent.core.model.ToolCall;
import com.seven.agent.core.tool.InMemoryToolRegistry;
import com.seven.agent.model.mock.CurrentTimeTool;
import com.seven.agent.model.mock.EchoTool;
import com.seven.agent.model.mock.MockModelClient;

/**
 * A minimal Mock Agent example.
 * <p>
 * Demonstrates:
 * 1. Creating a ModelClient (Mock, no real LLM needed)
 * 2. Registering tools
 * 3. Creating an Agent with system prompt
 * 4. Running the agent with user input
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=com.seven.agent.examples.MockAgentExample
 * Or:  run directly from IDE.
 */
public class MockAgentExample {

    public static void main(String[] args) {
        System.out.println("=== Java Agent Framework - Mock Agent Example ===\n");

        // --------------------------------------------
        // 1. Create a Mock ModelClient (no real LLM needed)
        // --------------------------------------------
        // Scripted mode: we control exactly what the "model" says
        MockModelClient modelClient = MockModelClient.scripted()
                .respondToolCalls(ToolCall.of("call_1", "get_current_time",
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()))
                .respondText("Based on the tool result, the current time has been retrieved. " +
                        "This is the final answer from the mock agent.");

        // --------------------------------------------
        // 2. Register tools
        // --------------------------------------------
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new CurrentTimeTool());
        registry.register(new EchoTool());

        // --------------------------------------------
        // 3. Create Agent
        // --------------------------------------------
        AgentConfig config = new AgentConfig(
                "mock-agent-v1",
                "You are a helpful assistant. Use tools when needed to answer questions.",
                modelClient,
                registry,
                10  // max steps
        );

        Agent agent = new SimpleAgent(config);

        // --------------------------------------------
        // 4. Run the agent
        // --------------------------------------------
        String userInput = "What time is it now?";
        System.out.println("User: " + userInput);
        System.out.println();

        String response = agent.run(userInput);

        System.out.println("Agent: " + response);
        System.out.println();

        // --------------------------------------------
        // 5. Rule-based mode demo (no scripting needed)
        // --------------------------------------------
        System.out.println("=== Rule-based mode ===\n");

        MockModelClient ruleClient = MockModelClient.ruleBased();
        AgentConfig ruleConfig = new AgentConfig(
                "mock-agent-rule",
                "You are a helpful assistant.",
                ruleClient,
                registry,
                5
        );
        Agent ruleAgent = new SimpleAgent(ruleConfig);

        System.out.println("User: Can you echo 'Hello Agent Framework'?");
        System.out.println("Agent: " + ruleAgent.run("Can you echo 'Hello Agent Framework'?"));
        System.out.println();

        System.out.println("=== Done ===");
    }
}
