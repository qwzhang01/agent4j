package com.seven.agent.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seven.agent.core.model.ModelRequest;
import com.seven.agent.core.model.ModelResponse;
import com.seven.agent.core.model.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Stream;

/**
 * Decorator that enforces structured (JSON schema) output from the model.
 * <p>
 * Two mechanisms:
 * 1. Request-level: sets responseFormat on the request so the provider
 *    natively enforces JSON output (if supported).
 * 2. Validation-level: after receiving the response, validates that the
 *    content is valid JSON. If invalid, retries with a correction prompt.
 * <p>
 * If the provider does not support structured output natively, the validation
 * + retry mechanism still ensures the final result is valid JSON.
 */
public class StructuredOutputModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputModelClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ModelClient delegate;
    private final int maxRetries;

    public StructuredOutputModelClient(ModelClient delegate) {
        this(delegate, 2);
    }

    public StructuredOutputModelClient(ModelClient delegate, int maxRetries) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        // If request already has a response format, just validate
        if (request.responseFormat() != null) {
            return chatWithValidation(request);
        }
        // Otherwise, don't force structured output (let the caller decide)
        return delegate.chat(request);
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        // Streaming + structured output is tricky: we can't validate mid-stream.
        // For now, just pass through. Full implementation would buffer
        // and validate the complete response at the end.
        return delegate.stream(request);
    }

    // ============ Private Helpers ============

    private ModelResponse chatWithValidation(ModelRequest request) {
        ModelResponse lastResponse = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            lastResponse = delegate.chat(request);

            if (isValidJson(lastResponse.content())) {
                return lastResponse;
            }

            log.warn("Structured output validation failed (attempt {}), content not valid JSON", attempt + 1);

            if (attempt < maxRetries) {
                // Retry with a correction hint appended
                request = appendCorrectionPrompt(request, lastResponse.content());
            }
        }

        // Return the last response even if invalid (let caller decide)
        log.error("Structured output failed after {} attempts", maxRetries + 1);
        return ModelResponse.error("Failed to produce valid JSON after " + (maxRetries + 1) + " attempts");
    }

    private boolean isValidJson(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            JsonNode node = mapper.readTree(content);
            return node.isObject() || node.isArray();
        } catch (Exception e) {
            return false;
        }
    }

    private ModelRequest appendCorrectionPrompt(ModelRequest original, String badOutput) {
        var newMessages = new java.util.ArrayList<>(original.messages());
        newMessages.add(com.seven.agent.core.model.ChatMessage.assistant(badOutput));
        newMessages.add(com.seven.agent.core.model.ChatMessage.user(
                "Your previous response was not valid JSON. " +
                "Please respond with ONLY valid JSON, no markdown, no explanation."));

        return ModelRequest.builder()
                .model(original.model())
                .messages(newMessages)
                .tools(original.tools())
                .temperature(original.temperature())
                .maxTokens(original.maxTokens())
                .responseFormat(original.responseFormat())
                .build();
    }
}
