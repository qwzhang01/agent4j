package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.product.ProductContext;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.1 validator tests: structure rules + reference rules (with available-name
 * listings), all errors reported in one pass.
 */
class DefinitionValidatorTest {

    private final DefinitionValidator validator = new DefinitionValidator();

    // ============ Fixtures ============

    /** A context with one model, two tools, one context builder - names to miss on purpose. */
    private ProductContext context() {
        return new ProductContext()
                .registerModel("openai", new NoopModelClient())
                .registerModel("deepseek", new NoopModelClient())
                .registerTool("order-query", new NoopTool("order-query"))
                .registerTool("refund-search", new NoopTool("refund-search"))
                .registerContextBuilder("rich-memory", new NoopContextBuilder());
    }

    private AgentDefinition definition(String yaml) {
        return new AgentDefinitionParser().parse(yaml);
    }

    private String validYaml() {
        return """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: valid-bot
                spec:
                  persona:
                    systemPrompt: "You help."
                    temperature: 0.5
                  model:
                    provider: openai
                    fallback: deepseek
                  tools:
                    - ref: order-query
                  memory:
                    shortTerm:
                      strategy: window
                      maxMessages: 20
                """;
    }

    // ============ Happy path ============

    @Test
    void validDefinitionProducesNoErrors() {
        List<ValidationError> errors = validator.validate(definition(validYaml()), context());
        assertTrue(errors.isEmpty(), () -> "expected no errors, got: " + errors);
    }

    // ============ Structure rules ============

    @Test
    void missingSystemPromptIsReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    temperature: 0.5
                  model:
                    provider: openai
                """), context());
        assertEquals(1, errors.size());
        assertEquals("spec.persona", errors.get(0).path());
    }

    // ============ M13.4: promptRef ============

    @Test
    void promptRefAloneIsValidWhenPromptIsPublished() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    promptRef: { name: support-system, channel: stable }
                  model:
                    provider: openai
                """), contextWithPrompts());
        assertTrue(errors.isEmpty(), () -> "got: " + errors);
    }

    @Test
    void systemPromptAndPromptRefAreMutuallyExclusive() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "inline"
                    promptRef: { name: support-system }
                  model:
                    provider: openai
                """), contextWithPrompts());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("mutually exclusive"));
    }

    @Test
    void danglingPromptRefListsAvailablePrompts() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    promptRef: { name: support-sistem }
                  model:
                    provider: openai
                """), contextWithPrompts());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("support-system"),
                "error should list available prompts, got: " + errors.get(0).message());
    }

    @Test
    void promptRefWithoutPromptManagerIsRejected() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    promptRef: { name: support-system }
                  model:
                    provider: openai
                """), context());   // no PromptManager attached
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("PromptManager"));
    }

    @Test
    void invalidPromptRefChannelIsRejected() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    promptRef: { name: support-system, channel: beta }
                  model:
                    provider: openai
                """), contextWithPrompts());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("beta"));
    }

    // ============ M13.5: workflow + ambient validation ============

    @Test
    void danglingWorkflowReferenceListsAvailable() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  workflow: ghost-flow
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("workflow"));
    }

    @Test
    void ambientProblemsAreAllReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  ambient:
                    - instructionId: ""
                      description: ""
                      trigger: { onEvent: "a", schedule: "PT1M" }
                      importance: LOUD
                      messageTemplate: ""
                    - instructionId: bad-dur
                      description: d
                      trigger: { schedule: "10-minutes" }
                      importance: INFO
                      messageTemplate: "t"
                """), context());
        // entry 0: blank id + blank description + trigger both-set + bad
        // importance + blank template = 5; entry 1: bad duration = 1; total 6
        assertEquals(6, errors.size(), () -> "got: " + errors);
    }

    @Test
    void validAmbientDeclarationsProduceNoErrors() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  ambient:
                    - instructionId: watch
                      description: watch things
                      trigger: { onEvent: "evt" }
                      importance: CRITICAL
                      messageTemplate: "alert {$.id}"
                """), context());
        assertTrue(errors.isEmpty(), () -> "got: " + errors);
    }

    /** A context that also has a PromptManager with one published prompt. */
    private ProductContext contextWithPrompts() {
        io.github.qwzhang01.agent.product.prompt.PromptManager prompts =
                new io.github.qwzhang01.agent.product.prompt.PromptManager();
        prompts.publish("support-system", "managed prompt content");
        return context().withPromptManager(prompts);
    }

    @Test
    void temperatureOutOfRangeIsReported() {
        for (String bad : new String[]{"-0.1", "2.5"}) {
            List<ValidationError> errors = validator.validate(definition("""
                    apiVersion: v1
                    kind: Agent
                    metadata:
                      name: x
                    spec:
                      persona:
                        systemPrompt: "hi"
                        temperature: %s
                      model:
                        provider: openai
                    """.formatted(bad)), context());
            assertEquals(1, errors.size(), "temperature " + bad + " should fail");
            assertEquals("spec.persona.temperature", errors.get(0).path());
        }
    }

    @Test
    void memorySectionsAreMutuallyExclusive() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  memory:
                    shortTerm:
                      strategy: window
                      maxMessages: 10
                    contextBuilder: rich-memory
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).path().startsWith("spec.memory"));
    }

    @Test
    void unsupportedStrategyIsReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  memory:
                    shortTerm:
                      strategy: lru
                      maxMessages: 10
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("[window]"));
    }

    @Test
    void nonPositiveMaxMessagesIsReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  memory:
                    shortTerm:
                      strategy: window
                      maxMessages: 0
                """), context());
        assertEquals(1, errors.size());
    }

    // ============ Reference rules (D1: names -> registry) ============

    @Test
    void danglingModelProviderListsAvailableModels() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: opanai     # typo
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("opanai"));
        assertTrue(errors.get(0).message().contains("openai"),
                "error should list available models, got: " + errors.get(0).message());
    }

    @Test
    void danglingFallbackIsReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                    fallback: local-llm
                """), context());
        assertEquals(1, errors.size());
        assertEquals("spec.model.fallback", errors.get(0).path());
    }

    @Test
    void danglingToolRefListsAvailableTools() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  tools:
                    - ref: order-qery     # typo
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("order-qery"));
        assertTrue(errors.get(0).message().contains("order-query"),
                "error should list available tools, got: " + errors.get(0).message());
    }

    @Test
    void duplicateToolRefIsReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  tools:
                    - ref: order-query
                    - ref: order-query
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("duplicate"));
    }

    @Test
    void danglingContextBuilderIsReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  memory:
                    contextBuilder: basic-memory
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("rich-memory"));
    }

    // ============ All errors in one pass ============

    @Test
    void multipleErrorsAreAllReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    temperature: 5.0
                  model:
                    provider: nope
                  tools:
                    - ref: also-nope
                    - ref: also-nope
                """), context());
        // prompt + temperature + provider + dangling[0] + (duplicate + dangling)[1] = 6
        assertEquals(6, errors.size(), () -> "all errors in one pass, got: " + errors);
    }

    // ============ M13.3: inline http tool declarations ============

    @Test
    void validHttpDeclProducesNoErrors() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  tools:
                    - http:
                        name: weather-query
                        description: 查询天气
                        endpoint: https://api.example.com/now
                        method: GET
                        params:
                          city: { in: query, type: string, required: true }
                        response:
                          extract: "$.data.temperature"
                        auth:
                          type: bearer
                          token: "${env:WEATHER_TOKEN}"
                        timeoutSeconds: 3
                """), context());
        assertTrue(errors.isEmpty(), () -> "got: " + errors);
    }

    @Test
    void httpAndRefToolsShareOneNameNamespace() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  tools:
                    - ref: order-query
                    - http:
                        name: order-query
                        description: 同名 http 工具
                        endpoint: https://api.example.com/x
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("duplicate"));
    }

    @Test
    void httpDeclStructuralProblemsAreAllReported() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  tools:
                    - http:
                        name: bad-tool
                        description: ""
                        endpoint: ftp://not-http
                        method: PATCH
                        params:
                          city: { in: header, required: true }
                          note: { in: body, required: false }
                        response:
                          extract: "data.temperature"
                        auth:
                          type: basic
                          token: ""
                        timeoutSeconds: -1
                """), context());
        // blank description + bad endpoint + bad method + bad in + bad extract
        // + bad auth type + blank token + bad timeout = 8
        // (body-param-with-GET does not fire here because method is PATCH, not GET)
        assertEquals(8, errors.size(), () -> "all structural errors in one pass, got: " + errors);
    }

    @Test
    void bodyParamWithGetIsRejected() {
        List<ValidationError> errors = validator.validate(definition("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  tools:
                    - http:
                        name: t
                        description: d
                        endpoint: https://api.example.com/x
                        method: GET
                        params:
                          note: { in: body, required: false }
                """), context());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("GET"));
    }

    // ============ Test doubles ============

    private record NoopModelClient() implements ModelClient {
        @Override
        public io.github.qwzhang01.agent.core.model.ModelResponse chat(
                io.github.qwzhang01.agent.core.model.ModelRequest request) {
            throw new UnsupportedOperationException("not called in validator tests");
        }

        @Override
        public java.util.stream.Stream<io.github.qwzhang01.agent.core.model.StreamEvent> stream(
                io.github.qwzhang01.agent.core.model.ModelRequest request) {
            throw new UnsupportedOperationException("not called in validator tests");
        }
    }

    private record NoopTool(String name) implements Tool {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "noop";
        }

        @Override
        public String getParametersSchema() {
            return null;
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "noop";
        }
    }

    private static final class NoopContextBuilder implements ContextBuilder {
        @Override
        public List<ChatMessage> build(AgentConfig config, AgentState state) {
            return state.getMessages();
        }
    }
}
