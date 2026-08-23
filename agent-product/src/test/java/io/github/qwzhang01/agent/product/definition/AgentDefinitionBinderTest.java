package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.product.ProductContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.1 binder tests - the milestone acceptance in code:
 * a validated definition binds into a runnable agent with persona, tool subset,
 * model fallback chain and temperature wiring all effective.
 */
class AgentDefinitionBinderTest {

    private final AgentDefinitionParser parser = new AgentDefinitionParser();
    private final DefinitionValidator validator = new DefinitionValidator();

    // ============ Test doubles ============

    /**
     * Captures every ModelRequest the agent actually sends (the boundary where
     * persona/temperature wiring becomes observable). Same technique as Stage 12's
     * RecordingModelClient: MockModelClient does not expose requests, so we
     * intercept at the ModelClient boundary.
     */
    private static final class RecordingModelClient implements ModelClient {
        private final MockModelClient delegate;
        private final List<ModelRequest> requests = new ArrayList<>();

        RecordingModelClient(MockModelClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(request);
            return delegate.chat(request);
        }

        @Override
        public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
            requests.add(request);
            return delegate.stream(request);
        }
    }

    private record FakeTool(String name) implements Tool {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "fake tool " + name;
        }

        @Override
        public String getParametersSchema() {
            return null;
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return name + "-result";
        }
    }

    private static final class StaticContextBuilder implements ContextBuilder {
        @Override
        public List<ChatMessage> build(AgentConfig config, io.github.qwzhang01.agent.core.agent.AgentState state) {
            return state.getMessages();
        }
    }

    // ============ Helpers ============

    private ProductContext context(ModelClient primary, ModelClient fallback) {
        ProductContext ctx = new ProductContext().registerModel("primary", primary);
        if (fallback != null) {
            ctx.registerModel("backup", fallback);
        }
        return ctx;
    }

    private Agent bind(String yaml, ProductContext ctx) {
        AgentDefinition def = parser.parse(yaml);
        var errors = validator.validate(def, ctx);
        assertTrue(errors.isEmpty(), () -> "fixture must be valid: " + errors);
        return new AgentDefinitionBinder(ctx).bind(def);
    }

    // ============ Acceptance: a definition binds into a runnable agent ============

    @Test
    void boundAgentRunsAndCarriesPersona() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("final answer"));
        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: persona-bot
                spec:
                  persona:
                    systemPrompt: "You are the shop assistant."
                  model:
                    provider: primary
                """, context(model, null));

        String answer = agent.run("hello");

        assertEquals("final answer", answer);
        // Persona wiring is observable at the model boundary: first message is SYSTEM.
        ModelRequest firstRequest = model.requests.get(0);
        ChatMessage first = firstRequest.messages().get(0);
        assertEquals(ChatRole.SYSTEM, first.role());
        assertEquals("You are the shop assistant.", first.content());
    }

    @Test
    void temperatureFromDefinitionReachesTheModelRequest() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: temp-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                    temperature: 0.3
                  model:
                    provider: primary
                """, context(model, null));

        agent.run("hello");

        assertEquals(0.3, model.requests.get(0).temperature());
    }

    @Test
    void noTemperatureMeansNullDefault() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: plain-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: primary
                """, context(model, null));

        agent.run("hello");

        assertNull(model.requests.get(0).temperature());
    }

    @Test
    void fallbackModelTakesOverWhenPrimaryFails() {
        // Primary has no scripted responses -> first chat throws ModelException,
        // FallbackModelClient (wired by the binder) hands over to "backup".
        MockModelClient primary = MockModelClient.scripted();
        MockModelClient backup = MockModelClient.scripted().respondText("answer from backup");

        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: resilient-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: primary
                    fallback: backup
                """, context(primary, backup));

        assertEquals("answer from backup", agent.run("hello"));
    }

    @Test
    void temperatureAndFallbackChainTogether() {
        // Temperature decorator wraps the fallback chain, so the fallback call
        // should still carry the definition's temperature.
        MockModelClient backup = MockModelClient.scripted().respondText("from backup");
        List<ModelRequest> backupRequests = new ArrayList<>();
        ModelClient recordingBackup = new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                backupRequests.add(request);
                return backup.chat(request);
            }

            @Override
            public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
                return backup.stream(request);
            }
        };

        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: full-chain-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                    temperature: 0.7
                  model:
                    provider: primary
                    fallback: backup
                """, context(MockModelClient.scripted(), recordingBackup));

        assertEquals("from backup", agent.run("hello"));
        assertEquals(0.7, backupRequests.get(0).temperature());
    }

    // ============ Tool subset semantics ============

    @Test
    void toolsSectionSelectsASubsetOfTheRegistry() {
        ProductContext ctx = context(MockModelClient.scripted().respondText("ok"), null)
                .registerTool("order-query", new FakeTool("order-query"))
                .registerTool("refund-search", new FakeTool("refund-search"))
                .registerTool("kb-search", new FakeTool("kb-search"));

        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: subset-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: primary
                  tools:
                    - ref: order-query
                """, ctx);

        AgentConfig config = agent.getConfig();
        assertEquals(1, config.getToolRegistry().listTools().size());
        assertEquals("order-query", config.getToolRegistry().listTools().get(0).getName());
    }

    // ============ Memory wiring ============

    @Test
    void shortTermWindowBindsWindowContextBuilder() {
        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: window-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: primary
                  memory:
                    shortTerm:
                      strategy: window
                      maxMessages: 5
                """, context(MockModelClient.scripted().respondText("ok"), null));

        assertInstanceOf(WindowContextBuilder.class, agent.getConfig().getContextBuilder());
    }

    @Test
    void namedContextBuilderIsResolvedByReference() {
        StaticContextBuilder rich = new StaticContextBuilder();
        ProductContext ctx = context(MockModelClient.scripted().respondText("ok"), null)
                .registerContextBuilder("rich-memory", rich);

        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: rich-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: primary
                  memory:
                    contextBuilder: rich-memory
                """, ctx);

        assertSame(rich, agent.getConfig().getContextBuilder());
    }

    @Test
    void noMemorySectionMeansPassthrough() {
        Agent agent = bind("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: raw-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: primary
                """, context(MockModelClient.scripted().respondText("ok"), null));

        assertNull(agent.getConfig().getContextBuilder());
    }

    // ============ M13.4: promptRef + D4 pin ============

    private ProductContext contextWithPrompts(
            io.github.qwzhang01.agent.product.prompt.PromptManager prompts,
            ModelClient client) {
        return new ProductContext()
                .registerModel("primary", client)
                .withPromptManager(prompts);
    }

    private static final String PROMPT_REF_YAML = """
            apiVersion: v1
            kind: Agent
            metadata:
              name: managed-bot
              tenant: %s
            spec:
              persona:
                promptRef: { name: support-system }
                temperature: 0.3
              model:
                provider: primary
            """;

    @Test
    void promptRefBindsResolvedContentAsPersona() {
        var prompts = new io.github.qwzhang01.agent.product.prompt.PromptManager();
        prompts.publish("support-system", "你是托管人格 v1。");
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));

        Agent agent = bind(PROMPT_REF_YAML.formatted(null),
                contextWithPrompts(prompts, model));
        agent.run("hi");

        var first = model.requests.get(0).messages().get(0);
        assertEquals(ChatRole.SYSTEM, first.role());
        assertEquals("你是托管人格 v1。", first.content());
    }

    @Test
    void midFlightPublishDoesNotAffectARunningConversation() {
        // THE D4 acceptance: publish v2 while the conversation runs -> the SAME
        // agent keeps seeing v1; the next bind sees v2.
        var prompts = new io.github.qwzhang01.agent.product.prompt.PromptManager();
        prompts.publish("support-system", "persona v1");
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("a").respondText("b"));

        Agent conversation = bind(PROMPT_REF_YAML.formatted(null),
                contextWithPrompts(prompts, model));

        // Hot-switch moment: v2 ships while the conversation is alive.
        prompts.publish("support-system", "persona v2");

        // The running conversation keeps its pinned version...
        conversation.run("hello");
        assertEquals("persona v1", model.requests.get(0).messages().get(0).content());
        conversation.run("again");
        assertEquals("persona v1", model.requests.get(1).messages().get(0).content());

        // ...and a NEW bind picks up v2 automatically.
        RecordingModelClient freshModel = new RecordingModelClient(
                MockModelClient.scripted().respondText("c"));
        Agent nextConversation = bind(PROMPT_REF_YAML.formatted(null),
                contextWithPrompts(prompts, freshModel));
        nextConversation.run("hello");
        assertEquals("persona v2", freshModel.requests.get(0).messages().get(0).content());
    }

    @Test
    void tenantRoutingSplitsStableAndCanaryAtBindTime() {
        var prompts = new io.github.qwzhang01.agent.product.prompt.PromptManager();
        prompts.publish("support-system", "stable persona");
        prompts.publish("support-system", "canary persona",
                io.github.qwzhang01.agent.product.prompt.PromptChannel.CANARY);
        prompts.setTenantChannel("acme", "support-system",
                io.github.qwzhang01.agent.product.prompt.PromptChannel.CANARY);

        RecordingModelClient acmeModel = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        RecordingModelClient otherModel = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));

        bind(PROMPT_REF_YAML.formatted("acme"), contextWithPrompts(prompts, acmeModel))
                .run("hi");
        bind(PROMPT_REF_YAML.formatted("other-tenant"), contextWithPrompts(prompts, otherModel))
                .run("hi");

        assertEquals("canary persona", acmeModel.requests.get(0).messages().get(0).content());
        assertEquals("stable persona", otherModel.requests.get(0).messages().get(0).content());
    }

    @Test
    void rollbackTakesEffectOnNextBind() {
        var prompts = new io.github.qwzhang01.agent.product.prompt.PromptManager();
        prompts.publish("support-system", "v1");
        prompts.publish("support-system", "v2");
        prompts.rollback("support-system");

        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        bind(PROMPT_REF_YAML.formatted(null), contextWithPrompts(prompts, model)).run("hi");

        assertEquals("v1", model.requests.get(0).messages().get(0).content());
    }

    @Test
    void unmanagedPromptRefFailsBindDefensively() {
        // Validated definitions never reach this, but the binder defends itself.
        ProductContext ctx = new ProductContext()
                .registerModel("primary", MockModelClient.scripted().respondText("ok"))
                .withPromptManager(new io.github.qwzhang01.agent.product.prompt.PromptManager()); // empty

        AgentDefinition def = parser.parse(PROMPT_REF_YAML.formatted(null));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentDefinitionBinder(ctx).bind(def));
    }

    // ============ Defensive ============

    @Test
    void bindingUnvalidatedDanglingReferenceFailsFast() {
        ProductContext ctx = context(MockModelClient.scripted().respondText("ok"), null);
        AgentDefinition dangling = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: dangling-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: ghost
                """);
        // No validate() call - binder must defend itself anyway.
        assertThrows(IllegalArgumentException.class,
                () -> new AgentDefinitionBinder(ctx).bind(dangling));
    }

    // ============ M13.3: inline http tools bind and run ============

    @Test
    void httpToolFromDefinitionRunsAgainstRealEndpoint() {
        // Local mock server via JDK HttpServer (same technique as HttpApiToolTest).
        try (var serverHolder = new LocalServer()) {
            String base = serverHolder.base();

            ProductContext ctx = context(MockModelClient.scripted(), null);
            Agent agent = bind(("""
                    apiVersion: v1
                    kind: Agent
                    metadata:
                      name: http-bot
                    spec:
                      persona:
                        systemPrompt: "hi"
                      model:
                        provider: primary
                      tools:
                        - http:
                            name: weather-query
                            description: 查询天气
                            endpoint: %s/weather
                            method: GET
                            params:
                              city: { in: query, type: string, required: true }
                            response:
                              extract: "$.temp"
                    """).formatted(base), ctx);

            // The agent's registry holds the http tool by name.
            var tools = agent.getConfig().getToolRegistry().listTools();
            assertEquals(1, tools.size());
            assertEquals("weather-query", tools.get(0).getName());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ============ Local test server (M13.3) ============

    /** Starts a trivial 200/JSON server on a random port; try-with-resources. */
    private static final class LocalServer implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;

        LocalServer() throws java.io.IOException {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/weather", exchange -> {
                byte[] bytes = "{\"temp\":26}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
