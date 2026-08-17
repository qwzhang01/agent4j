package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.sandbox.Sandbox;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import io.github.qwzhang01.agent.sandbox.classloader.ClassLoaderSandbox;
import io.github.qwzhang01.agent.sandbox.tools.SandboxTool;

/**
 * End-to-end demo: the MODEL (not the developer) triggers sandbox execution.
 * <p>
 * Complete chain:
 * model toolCall(sandbox_execute)
 * -> ReActAgentLoop -> DefaultToolExecutor -> SandboxTool
 * -> ClassLoaderSandbox (compile / load with blocked-list / run / timeout)
 * -> SandboxResult -> JSON tool message back to model
 * -> model reads result -> final answer
 * <p>
 * Scenario: user asks to compute sum 1-100.
 * Mock model "writes" Java code and calls sandbox_execute,
 * reads the execution result, then answers.
 */
public class SandboxAgentExample {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("=== Sandbox Agent End-to-End Demo ===\n");
        System.out.println("Scenario: model writes code, sandbox executes it, "
                + "model reads result and answers.\n");

        // 1. Sandbox + tool registration
        Sandbox sandbox = new ClassLoaderSandbox(
                SandboxSpec.builder().timeout(java.time.Duration.ofSeconds(5)).build());
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new SandboxTool(sandbox));
        System.out.println("Registered 1 tool: sandbox_execute\n");

        // 2. Mock model scripted to: (1) call sandbox_execute with generated code,
        //    (2) read the result, (3) produce the final answer.
        ModelClient modelClient = MockModelClient.scripted()
                .respondToolCalls(ToolCall.of("call_1", "sandbox_execute",
                        sandboxArgs("Generated", """
                                public class Generated {
                                    public static String run() {
                                        int sum = 0;
                                        for (int i = 1; i <= 100; i++) sum += i;
                                        return "Sum 1-100 = " + sum;
                                    }
                                }
                                """)))
                .respondText("The sum of 1 to 100 is 5050. "
                        + "I generated Java code and executed it in the sandbox.");

        // 3. Agent with the sandbox tool
        AgentConfig config = new AgentConfig(
                "coding-agent",
                "You are a coding assistant. Use sandbox_execute to run Java code.",
                modelClient,
                registry,
                10
        );
        Agent agent = new SimpleAgent(config);

        // 4. Run the full loop
        System.out.println("--- User: \"Compute the sum of 1 to 100\" ---\n");
        String answer = agent.run("Compute the sum of 1 to 100");
        System.out.println("--- Agent: \"" + answer + "\" ---");

        // 5. Blocked-code scenario: model tries file access, sandbox blocks it,
        //    model sees the denial and answers without the file.
        System.out.println("\n=== Second scenario: sandbox blocks file access ===\n");

        InMemoryToolRegistry registry2 = new InMemoryToolRegistry();
        registry2.register(new SandboxTool(new ClassLoaderSandbox()));
        ModelClient modelClient2 = MockModelClient.scripted()
                .respondToolCalls(ToolCall.of("call_1", "sandbox_execute",
                        sandboxArgs("Generated", """
                                import java.io.File;
                                public class Generated {
                                    public static String run() {
                                        return new File("/etc/passwd").exists() ? "exists" : "missing";
                                    }
                                }
                                """)))
                .respondText("I could not read the file: sandbox blocked access to java.io.File. "
                        + "File system access is not allowed.");

        Agent agent2 = new SimpleAgent(new AgentConfig(
                "coding-agent-2", "You are a coding assistant.",
                modelClient2, registry2, 10));

        System.out.println("--- User: \"Check if /etc/passwd exists\" ---\n");
        String answer2 = agent2.run("Check if /etc/passwd exists");
        System.out.println("--- Agent: \"" + answer2 + "\" ---");

        System.out.println("\n=== Done ===");
    }

    /**
     * Build the sandbox_execute tool arguments: {class_name, code}.
     */
    private static JsonNode sandboxArgs(String className, String code) {
        ObjectNode node = mapper.createObjectNode();
        node.put("class_name", className);
        node.put("code", code);
        return node;
    }
}
