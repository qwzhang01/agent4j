package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.channel.ChannelContext;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.product.ProductContext;
import io.github.qwzhang01.agent.product.prompt.PromptChannel;
import io.github.qwzhang01.agent.product.prompt.PromptManager;
import io.github.qwzhang01.agent.product.tenant.TenantAgentConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.5 tenant overlay tests (D7): what a tenant may tune on top of a
 * definition - and what it may not (isolation between tenants).
 */
class TenantOverlayBinderTest {

    private final AgentDefinitionParser parser = new AgentDefinitionParser();
    private final DefinitionValidator validator = new DefinitionValidator();

    private record FakeTool(String name) implements Tool {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "fake";
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

    /** Two tenants, one definition each (same shape, different tenant ids). */
    private String yaml(String tenant) {
        return """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: bot-%s
                  tenant: %s
                spec:
                  persona:
                    promptRef: { name: support-system }
                  model:
                    provider: primary
                  tools:
                    - ref: order-query
                    - ref: refund-search
                """.formatted(tenant, tenant);
    }

    private ProductContext baseContext() {
        PromptManager prompts = new PromptManager();
        prompts.publish("support-system", "stable persona");
        prompts.publish("support-system", "canary persona", PromptChannel.CANARY);
        return new ProductContext()
                .registerModel("primary", MockModelClient.scripted().respondText("p"))
                .registerModel("budget", MockModelClient.scripted().respondText("b"))
                .registerTool("order-query", new FakeTool("order-query"))
                .registerTool("refund-search", new FakeTool("refund-search"))
                .withPromptManager(prompts);
    }

    // ============ Model overlay ============

    @Test
    void tenantModelOverlayReplacesPrimaryProvider() {
        ProductContext ctx = baseContext()
                .registerTenantConfig(new TenantAgentConfig("acme", null, "budget", null, null));

        var agent = bindValidated(yaml("acme"), ctx);

        // The tenant's model is wired (observable via the run output).
        assertEquals("b", agent.run("hi"));
        // No overlay for the other tenant - stays on the definition's model.
        var plain = bindValidated(yaml("other"), ctx);
        assertEquals("p", plain.run("hi"));
    }

    // ============ Tool restriction ============

    @Test
    void tenantDisabledToolsShrinkTheSubset() {
        ProductContext ctx = baseContext()
                .registerTenantConfig(new TenantAgentConfig("acme", null, null,
                        java.util.Set.of("refund-search"), null));

        var restricted = bindValidated(yaml("acme"), ctx);
        var tools = restricted.getConfig().getToolRegistry().listTools();
        assertEquals(1, tools.size());
        assertEquals("order-query", tools.get(0).getName());

        // The other tenant keeps both tools - isolation.
        var full = bindValidated(yaml("other"), ctx);
        assertEquals(2, full.getConfig().getToolRegistry().listTools().size());
    }

    // ============ Prompt channel overlay ============

    @Test
    void tenantPromptChannelOverlayRoutesToCanary() {
        ProductContext ctx = baseContext()
                .registerTenantConfig(new TenantAgentConfig("acme", "canary", null, null, null));

        RecordingCapture acmeCapture = new RecordingCapture();
        RecordingCapture otherCapture = new RecordingCapture();
        ctx.registerModel("acme-model", acmeCapture);

        // Rewrite model per tenant via overlay (cleanest way to observe prompts).
        ProductContext acmeCtx = ctx;
        ProductContext otherCtx = baseContext()
                .registerTenantConfig(new TenantAgentConfig("other", "stable", "other-model", null, null))
                .registerModel("other-model", otherCapture);

        bindValidated(yamlWithModel("acme", "acme-model"), acmeCtx).run("hi");
        bindValidated(yamlWithModel("other", "other-model"), otherCtx).run("hi");

        assertEquals("canary persona", acmeCapture.systemPrompt);
        assertEquals("stable persona", otherCapture.systemPrompt);
    }

    @Test
    void operatorOverrideStillBeatsTenantOverlay() {
        // PromptManager.setTenantChannel (operator) > TenantAgentConfig.promptChannel.
        ProductContext ctx = baseContext();
        ctx.registerTenantConfig(new TenantAgentConfig("acme", "stable", null, null, null));
        ctx.promptManager().orElseThrow()
                .setTenantChannel("acme", "support-system", PromptChannel.CANARY);

        RecordingCapture capture = new RecordingCapture();
        ctx.registerModel("obs", capture);

        bindValidated(yamlWithModel("acme", "obs"), ctx).run("hi");

        assertEquals("canary persona", capture.systemPrompt);
    }

    // ============ Service account overlay (D7) ============

    @Test
    void tenantServiceAccountOverlayReplacesDerivedIdentity() {
        io.github.qwzhang01.agent.channel.identity.ServiceAccount provisioned =
                io.github.qwzhang01.agent.channel.identity.ServiceAccount.of("sa-provisioned-ops",
                        new io.github.qwzhang01.agent.channel.identity.AgentIdentity(
                                "ops-bot", "运维机器人", "platform"),
                        io.github.qwzhang01.agent.channel.identity.IdentityScope.capabilities("member"));
        ProductContext ctx = new ProductContext()
                .registerModel("primary", MockModelClient.scripted().respondText("ok"))
                .registerServiceAccount("ops-identity", provisioned)
                .registerTenantConfig(new TenantAgentConfig("acme", null, null, null, "ops-identity"));

        AgentDefinition def = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: ops-bot
                  tenant: acme
                spec:
                  persona:
                    systemPrompt: "你是运维助手。"
                  model:
                    provider: primary
                """);

        var binding = new AgentDefinitionBinder(ctx)
                .bindChannel(def, ChannelContext.of("ops-room", "alice"));

        binding.session().speak(
                io.github.qwzhang01.agent.channel.ChannelMessage.mention("ops-room", "alice", "检查一下"));

        // The provisioned account (not the derived sa-ops-bot-acme) resolved
        // the speaker - the tenant overlay's service identity is consumed.
        assertEquals("svc:sa-provisioned-ops",
                binding.session().lastResolvedIdentity().actor());
    }

    @Test
    void danglingServiceAccountReferenceFailsFastWithAvailableNames() {
        ProductContext ctx = new ProductContext()
                .registerModel("primary", MockModelClient.scripted().respondText("ok"))
                .registerTenantConfig(new TenantAgentConfig("acme", null, null, null, "ghost-account"));

        AgentDefinition def = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: ops-bot
                  tenant: acme
                spec:
                  persona:
                    systemPrompt: "你是运维助手。"
                  model:
                    provider: primary
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AgentDefinitionBinder(ctx).bindChannel(def,
                        ChannelContext.of("ops-room", "alice")));
        assertTrue(e.getMessage().contains("ghost-account"), e.getMessage());
    }

    // ============ Channel binding (ambient, M13.5 acceptance) ============

    @Test
    void yamlDefinitionBindsChannelAgentWithAmbientInstructions() {
        ProductContext ctx = new ProductContext()
                .registerModel("primary", MockModelClient.scripted().respondText("ok"))
                .registerTool("order-query", new FakeTool("order-query"));

        AgentDefinition def = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: ops-bot
                  tenant: acme
                spec:
                  persona:
                    systemPrompt: "你是运维助手。"
                  model:
                    provider: primary
                  tools:
                    - ref: order-query
                  ambient:
                    - instructionId: ticket-watch
                      description: 工单状态提醒
                      trigger: { onEvent: "ticket-updated" }
                      importance: WARN
                      messageTemplate: "工单 {$.ticketId} 状态变化"
                    - instructionId: heartbeat
                      description: 定期心跳汇报
                      trigger: { schedule: "PT10M" }
                      importance: INFO
                      messageTemplate: "例行检查完成"
                """);
        assertTrue(validator.validate(def, ctx).isEmpty(),
                () -> "fixture must be valid: " + validator.validate(def, ctx));

        var binding = new AgentDefinitionBinder(ctx)
                .bindChannel(def, ChannelContext.of("ops-room", "alice", "bob"));

        // The session is a live channel agent: a mention triggers a reply.
        String reply = binding.session().speak(
                io.github.qwzhang01.agent.channel.ChannelMessage.mention("ops-room", "alice", "检查一下"));
        assertEquals("ok", reply);
        // Channel history records the inbound message (agent replies are the
        // return value, not channel history - Stage 12 contract).
        assertEquals(1, binding.session().history().size());

        // Both ambient instructions were built with correct triggers.
        assertEquals(2, binding.ambient().size());
        var ticketWatch = binding.ambient().get(0);
        assertEquals("ticket-watch", ticketWatch.instructionId());
        assertInstanceOf(io.github.qwzhang01.agent.channel.ambient.AmbientInstruction.OnEvent.class,
                ticketWatch.trigger());
        assertEquals(io.github.qwzhang01.agent.channel.ambient.AmbientInstruction.Importance.WARN,
                ticketWatch.importance());
        // The message template renders against a payload.
        String rendered;
        try {
            rendered = ticketWatch.message().apply(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree("{\"ticketId\":\"T-42\"}"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("工单 T-42 状态变化", rendered);
        var heartbeat = binding.ambient().get(1);
        assertInstanceOf(io.github.qwzhang01.agent.channel.ambient.AmbientInstruction.Scheduled.class,
                heartbeat.trigger());
    }

    // ============ Helpers ============

    private io.github.qwzhang01.agent.core.agent.Agent bindValidated(String yaml, ProductContext ctx) {
        AgentDefinition def = parser.parse(yaml);
        var errors = validator.validate(def, ctx);
        assertTrue(errors.isEmpty(), () -> "fixture must be valid: " + errors);
        return new AgentDefinitionBinder(ctx).bind(def);
    }

    private String yamlWithModel(String tenant, String model) {
        return """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: bot-%s
                  tenant: %s
                spec:
                  persona:
                    promptRef: { name: support-system }
                  model:
                    provider: %s
                """.formatted(tenant, tenant, model);
    }

    /** Observes the system prompt the agent actually runs with. */
    private static final class RecordingCapture
            implements io.github.qwzhang01.agent.core.client.ModelClient {
        String systemPrompt;

        @Override
        public io.github.qwzhang01.agent.core.model.ModelResponse chat(
                io.github.qwzhang01.agent.core.model.ModelRequest request) {
            if (systemPrompt == null && !request.messages().isEmpty()) {
                var first = request.messages().get(0);
                if (first.role() == io.github.qwzhang01.agent.core.model.ChatRole.SYSTEM) {
                    systemPrompt = first.content();
                }
            }
            return io.github.qwzhang01.agent.core.model.ModelResponse.text("captured");
        }

        @Override
        public java.util.stream.Stream<io.github.qwzhang01.agent.core.model.StreamEvent> stream(
                io.github.qwzhang01.agent.core.model.ModelRequest request) {
            throw new UnsupportedOperationException("chat only");
        }
    }
}
