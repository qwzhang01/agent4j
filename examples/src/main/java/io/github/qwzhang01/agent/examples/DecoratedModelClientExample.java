package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.FallbackModelClient;
import io.github.qwzhang01.agent.core.client.RetryModelClient;
import io.github.qwzhang01.agent.core.client.StructuredOutputModelClient;
import io.github.qwzhang01.agent.core.client.TimeoutModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.model.mock.EchoTool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.model.openai.OpenAiModelClient;

import java.time.Duration;

/**
 * Example: stacking decorators to build a production-ready ModelClient.
 * <p>
 * Pattern:
 * <pre>{@code
 * StructuredOutput   <- outermost: validate JSON
 *   └─ Fallback       <- if primary fails, try secondary
 *      └─ Timeout     <- enforce timeout
 *         └─ Retry    <- retry transient failures
 *            └─ Real Client (OpenAI / Mock / ...)
 * }</pre>
 * <p>
 * Run with OpenAI: set OPENAI_API_KEY env var
 * Run without: uses MockModelClient
 */
public class DecoratedModelClientExample {

    public static void main(String[] args) {
        System.out.println("=== Decorated ModelClient Example ===\n");

        // --------------------------------------------
        // 1. Create the base ModelClient
        // --------------------------------------------
        var apiKey = System.getenv("OPENAI_API_KEY");
        var baseURL = System.getenv("OPENAI_BASE_URL");

        io.github.qwzhang01.agent.core.client.ModelClient baseClient;

        if (apiKey != null && !apiKey.isEmpty()) {
            // Real LLM
            String url = baseURL != null ? baseURL : "https://api.openai.com/v1";
            baseClient = new OpenAiModelClient(url, apiKey, "gpt-4o-mini");
            System.out.println("Using OpenAI client: " + url);
        } else {
            // Mock for demo without real API
            baseClient = MockModelClient.scripted()
                    .respondText("Hello! I am a decorated mock agent.");
            System.out.println("Using Mock client (set OPENAI_API_KEY for real LLM)");
        }

        // --------------------------------------------
        // 2. Stack decorators: Retry -> Timeout -> Fallback -> StructuredOutput
        // --------------------------------------------
        var retryClient = new RetryModelClient(baseClient, 3,
                Duration.ofMillis(500), 2.0);

        var timeoutClient = new TimeoutModelClient(retryClient, Duration.ofSeconds(30));

        // Fallback: primary (timeout+retry) -> secondary (mock, always works)
        var fallbackClient = new FallbackModelClient(timeoutClient,
                MockModelClient.scripted().respondText("Fallback response"));

        var decoratedClient = new StructuredOutputModelClient(fallbackClient);

        // --------------------------------------------
        // 3. Create Agent with decorated client
        // --------------------------------------------
        var registry = new InMemoryToolRegistry();
        registry.register(new EchoTool());

        var config = new AgentConfig(
                "decorated-agent",
                "You are a helpful assistant. Use tools when needed.",
                decoratedClient,
                registry,
                10
        );

        Agent agent = new SimpleAgent(config);

        // --------------------------------------------
        // 4. Run the agent
        // --------------------------------------------
        String userInput = "Hello, who are you?";
        System.out.println("\nUser: " + userInput);

        String response = agent.run(userInput);
        System.out.println("Agent: " + response);

        // --------------------------------------------
        // 5. Structured output example
        // --------------------------------------------
        System.out.println("\n=== Structured Output Example ===\n");

        var structuredRequest = ModelRequest.builder()
                .addMessage(io.github.qwzhang01.agent.core.model.ChatMessage.user(
                        "Return a JSON object with 'name' and 'age' fields."))
                .responseFormat(ModelRequest.ResponseFormat.json())
                .build();

        try {
            ModelResponse structuredResponse = decoratedClient.chat(structuredRequest);
            System.out.println("Response: " + structuredResponse.content());
            System.out.println("Finish reason: " + structuredResponse.finishReason());
        } catch (Exception e) {
            System.out.println("Structured output failed (expected with mock): " + e.getMessage());
        }

        System.out.println("\n=== Done ===");
    }
}
