package com.seven.agent.model.mock;

import com.seven.agent.core.client.ModelClient;
import com.seven.agent.core.client.ModelException;
import com.seven.agent.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

/**
 * Mock ModelClient for testing and development without a real LLM.
 * <p>
 * Two modes:
 * 1. Scripted: pre-configure a sequence of responses (for unit tests)
 * 2. Rule-based: simple keyword matching (for demos and smoke tests)
 * <p>
 * This demonstrates the ModelClient abstraction: Agent code doesn't know
 * or care whether it's talking to GPT-4, a local model, or a mock.
 */
public class MockModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(MockModelClient.class);

    private final Queue<ModelResponse> scriptedResponses = new LinkedBlockingQueue<>();
    private boolean ruleBasedMode = false;

    // ============ Builder ============

    public static MockModelClient scripted() {
        return new MockModelClient();
    }

    public static MockModelClient ruleBased() {
        var client = new MockModelClient();
        client.ruleBasedMode = true;
        return client;
    }

    // ============ Configuration ============

    /**
     * Add a scripted response (consumed in order).
     */
    public MockModelClient respond(ModelResponse response) {
        scriptedResponses.add(response);
        return this;
    }

    /**
     * Add a text response.
     */
    public MockModelClient respondText(String text) {
        scriptedResponses.add(ModelResponse.text(text));
        return this;
    }

    /**
     * Add a tool-call response.
     */
    public MockModelClient respondToolCalls(ToolCall... calls) {
        scriptedResponses.add(ModelResponse.toolCalls(List.of(calls)));
        return this;
    }

    // ============ ModelClient ============

    @Override
    public ModelResponse chat(ModelRequest request) {
        log.debug("MockModelClient received request with {} messages", request.messages().size());

        if (ruleBasedMode) {
            return ruleBasedResponse(request);
        }

        if (scriptedResponses.isEmpty()) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "No more scripted responses available");
        }

        return scriptedResponses.poll();
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        // Stage 1: simple implementation - call chat and emit as single event
        // Stage 1 TODO: implement real token-by-token streaming
        ModelResponse response = chat(request);
        return Stream.of(
                new StreamEvent.ContentDelta(response.content() != null ? response.content() : ""),
                new StreamEvent.Done(response)
        );
    }

    // ============ Rule-based Logic ============

    private ModelResponse ruleBasedResponse(ModelRequest request) {
        // Get last user message
        String userInput = "";
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatMessage msg = request.messages().get(i);
            if (msg.role() == ChatRole.USER) {
                userInput = msg.content();
                break;
            }
        }

        String lowerInput = userInput.toLowerCase();

        // Simple keyword matching
        if (lowerInput.contains("tool") || lowerInput.contains("calculate") || lowerInput.contains("time")) {
            // Simulate a tool call
            String toolName = lowerInput.contains("time") ? "get_current_time" : "echo";
            var args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            args.put("input", userInput);
            return ModelResponse.toolCalls(List.of(ToolCall.of("call_1", toolName, args)));
        }

        // Default: echo the user input
        return ModelResponse.text("Mock response to: \"" + userInput + "\"");
    }
}
