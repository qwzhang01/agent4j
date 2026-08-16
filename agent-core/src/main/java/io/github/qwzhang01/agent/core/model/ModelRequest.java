package io.github.qwzhang01.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Request sent to a model provider.
 *
 * @param model         model identifier (e.g. "gpt-4o", "doubao-pro")
 * @param messages      conversation messages
 * @param tools         available tool definitions (JSON schema), null if no tools
 * @param temperature   sampling temperature (0-2), null for provider default
 * @param maxTokens      max output tokens, null for provider default
 * @param stream         whether to stream the response
 * @param responseFormat response format spec (e.g. JSON schema), null for free text
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelRequest(
        String model,
        List<ChatMessage> messages,
        List<String> tools,
        Double temperature,
        Integer maxTokens,
        boolean stream,
        ResponseFormat responseFormat
) {
    public static Builder builder() {
        return new Builder();
    }

    // ============ Response Format ============

    /**
     * Specifies the desired output format.
     * - TEXT: free text (default)
     * - JSON: model must return valid JSON
     * - JSON_SCHEMA: model must return JSON conforming to the given schema
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResponseFormat(String type, String jsonSchema) {
        public static ResponseFormat text() {
            return new ResponseFormat("text", null);
        }
        public static ResponseFormat json() {
            return new ResponseFormat("json_object", null);
        }
        public static ResponseFormat jsonSchema(String schema) {
            return new ResponseFormat("json_schema", schema);
        }
    }

    // ============ Builder ============

    public static class Builder {
        private String model;
        private List<ChatMessage> messages = new ArrayList<>();
        private List<String> tools;
        private Double temperature;
        private Integer maxTokens;
        private boolean stream;
        private ResponseFormat responseFormat;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder addMessage(ChatMessage message) {
            this.messages.add(message);
            return this;
        }

        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public ModelRequest build() {
            return new ModelRequest(model, messages, tools, temperature, maxTokens, stream, responseFormat);
        }
    }
}
