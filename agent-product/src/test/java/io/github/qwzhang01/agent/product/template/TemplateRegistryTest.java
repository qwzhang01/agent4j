package io.github.qwzhang01.agent.product.template;

import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.product.ProductContext;
import io.github.qwzhang01.agent.product.definition.AgentDefinition;
import io.github.qwzhang01.agent.product.definition.AgentDefinitionBinder;
import io.github.qwzhang01.agent.product.definition.DefinitionValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.2 registry tests: registration discipline, built-ins, and the
 * template -> definition -> runnable agent acceptance chain.
 */
class TemplateRegistryTest {

    private static final String SUPPORT_TEMPLATE = """
            apiVersion: v1
            kind: AgentTemplate
            metadata:
              name: support-agent
              version: "1.0"
            variables:
              - name: tenantId
                required: true
            spec:
              persona:
                systemPrompt: "你是租户 ${tenantId} 的客服助手。"
                temperature: 0.3
              model:
                provider: openai
              tools:
                - ref: order-query
            """;

    // ============ Registration discipline ============

    @Test
    void duplicateRegistrationFailsFast() {
        TemplateRegistry registry = new TemplateRegistry()
                .register(AgentTemplate.parse(SUPPORT_TEMPLATE));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(AgentTemplate.parse(SUPPORT_TEMPLATE)));
    }

    @Test
    void replaceRequiresPriorRegistration() {
        TemplateRegistry registry = new TemplateRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.replace(AgentTemplate.parse(SUPPORT_TEMPLATE)));
    }

    @Test
    void replaceUpgradesForFutureInstantiationsOnly() {
        TemplateRegistry registry = new TemplateRegistry()
                .register(AgentTemplate.parse(SUPPORT_TEMPLATE));

        // Instance created under v1.
        AgentDefinition before = registry.instantiate("support-agent", "bot-1", null, Map.of("tenantId", "t"));

        // Explicit upgrade: same name, changed prompt.
        registry.replace(AgentTemplate.parse(
                SUPPORT_TEMPLATE.replace("客服助手", "售后助手")));

        AgentDefinition after = registry.instantiate("support-agent", "bot-2", null, Map.of("tenantId", "t"));

        assertEquals("你是租户 t 的客服助手。", before.spec().persona().systemPrompt());
        assertEquals("你是租户 t 的售后助手。", after.spec().persona().systemPrompt());
    }

    @Test
    void unknownTemplateNameListsAvailable() {
        TemplateRegistry registry = new TemplateRegistry()
                .register(AgentTemplate.parse(SUPPORT_TEMPLATE));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.instantiate("ghost", "bot", null, Map.of()));
        assertTrue(e.getMessage().contains("support-agent"), e.getMessage());
    }

    // ============ Built-ins ============

    @Test
    void builtinsShipSupportAndKnowledgeTemplates() {
        TemplateRegistry registry = TemplateRegistry.builtins();

        assertTrue(registry.get("support-agent").isPresent());
        assertTrue(registry.get("knowledge-assistant").isPresent());
        assertEquals(2, registry.names().size());

        // The built-ins parse into usable definitions with the documented defaults.
        AgentDefinition support = registry.instantiate(
                "support-agent", "support-bot", "acme", Map.of("tenantId", "acme"));
        assertTrue(support.spec().persona().systemPrompt().contains("七七商城"),
                "default brandName should apply: " + support.spec().persona().systemPrompt());
        assertEquals(2, support.spec().tools().size());
    }

    // ============ Acceptance: template + params -> runnable agent, zero Java per agent ============

    @Test
    void instantiatedDefinitionValidatesBindsAndRuns() {
        ProductContext context = new ProductContext()
                .registerModel("openai", MockModelClient.scripted().respondText("您好，老板！"))
                .registerModel("deepseek", MockModelClient.scripted().respondText("backup"))
                .registerTool("order-query", new FakeTool("order-query"))
                .registerTool("refund-policy-search", new FakeTool("refund-policy-search"));

        TemplateRegistry registry = TemplateRegistry.builtins();
        AgentDefinition def = registry.instantiate(
                "support-agent", "support-bot-acme", "acme", Map.of("tenantId", "acme"));

        // Full M13.1 pipeline on the M13.2 product.
        assertTrue(new DefinitionValidator().validate(def, context).isEmpty(),
                "instantiated definition must pass reference validation");
        var agent = new AgentDefinitionBinder(context).bind(def);
        assertEquals("您好，老板！", agent.run("帮我查订单"));

        // Tool subset from the template is wired.
        assertEquals(2, agent.getConfig().getToolRegistry().listTools().size());
    }

    // ============ Test doubles ============

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
}
