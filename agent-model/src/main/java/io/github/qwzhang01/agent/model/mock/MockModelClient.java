package io.github.qwzhang01.agent.model.mock;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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


    public static MockModelClient scripted() {
        return new MockModelClient();
    }

    public static MockModelClient ruleBased() {
        var client = new MockModelClient();
        client.ruleBasedMode = true;
        return client;
    }


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
        ModelResponse response = chat(request);
        return Stream.of(
                new StreamEvent.ContentDelta(response.content() != null ? response.content() : ""),
                new StreamEvent.Done(response)
        );
    }


    private ModelResponse ruleBasedResponse(ModelRequest request) {
        // Get last user message (text content, or text parts for multimodal messages)
        String userInput = "";
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatMessage msg = request.messages().get(i);
            if (msg.role() == ChatRole.USER) {
                userInput = extractText(msg);
                break;
            }
        }

        String lowerInput = userInput.toLowerCase();

        if (lowerInput.contains("tool") || lowerInput.contains("calculate") || lowerInput.contains("time")) {
            String toolName = lowerInput.contains("time") ? "get_current_time" : "echo";
            var args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            args.put("input", userInput);
            return ModelResponse.toolCalls(List.of(ToolCall.of("call_1", toolName, args)));
        }

        // Default: echo the user input
        return ModelResponse.text("Mock response to: \"" + userInput + "\"");
    }

    /**
     * Extracts displayable text from a message: plain content, or the text
     * parts of a multimodal message (images are summarized as placeholders).
     */
    private String extractText(ChatMessage msg) {
        if (msg.parts() == null) {
            return msg.content() != null ? msg.content() : "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : msg.parts()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            if (part instanceof ContentPart.TextPart tp) {
                sb.append(tp.text());
            } else if (part instanceof ContentPart.ImagePart) {
                sb.append("[image]");
            }
        }
        return sb.toString();
    }
}
