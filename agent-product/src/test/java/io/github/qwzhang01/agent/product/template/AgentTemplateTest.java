package io.github.qwzhang01.agent.product.template;

import io.github.qwzhang01.agent.product.definition.AgentDefinition;
import io.github.qwzhang01.agent.product.definition.DefinitionException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.2 template tests: parsing discipline, placeholder substitution,
 * fork-snapshot instantiation (D6).
 */
class AgentTemplateTest {

    private static final String TEMPLATE_YAML = """
            apiVersion: v1
            kind: AgentTemplate
            metadata:
              name: support-agent
              version: "1.0"
            variables:
              - name: tenantId
                required: true
              - name: brandName
                required: false
                default: "七七商城"
            spec:
              persona:
                systemPrompt: "你是 ${brandName} 的客服助手，租户 ${tenantId}。"
                temperature: 0.3
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

    // ============ Parsing discipline ============

    @Test
    void parsesTemplateWithVariablesAndSpecTree() {
        AgentTemplate template = AgentTemplate.parse(TEMPLATE_YAML);

        assertEquals("support-agent", template.metadata().name());
        assertEquals(2, template.variables().size());
        assertTrue(template.variables().get(0).required());
        assertEquals("七七商城", template.variables().get(1).defaultValue());
        assertEquals(true, template.spec().has("persona"));
    }

    @Test
    void wrongKindIsRejected() {
        assertThrows(DefinitionException.class, () -> AgentTemplate.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: x
                spec: {}
                """));
    }

    @Test
    void duplicateVariablesAreRejected() {
        assertThrows(DefinitionException.class, () -> AgentTemplate.parse("""
                apiVersion: v1
                kind: AgentTemplate
                metadata:
                  name: x
                variables:
                  - name: dup
                  - name: dup
                spec:
                  persona:
                    systemPrompt: "${dup}"
                """));
    }

    @Test
    void undeclaredPlaceholderIsRejectedAtLoadTime() {
        // A typo like ${tenantIID} must fail HERE, not silently live in a prompt.
        DefinitionException e = assertThrows(DefinitionException.class, () -> AgentTemplate.parse("""
                apiVersion: v1
                kind: AgentTemplate
                metadata:
                  name: x
                variables:
                  - name: tenantId
                spec:
                  persona:
                    systemPrompt: "tenant ${tenantIID}"
                """));
        assertTrue(e.getMessage().contains("tenantIID"), e.getMessage());
    }

    @Test
    void unknownFieldIsRejected() {
        assertThrows(DefinitionException.class, () -> AgentTemplate.parse("""
                apiVersion: v1
                kind: AgentTemplate
                metadata:
                  name: x
                  bogus: 1
                spec: {}
                """));
    }

    // ============ Instantiation ============

    @Test
    void substitutesParamsAndDefaults() {
        AgentDefinition def = AgentTemplate.parse(TEMPLATE_YAML)
                .instantiate("support-bot-acme", "acme", Map.of("tenantId", "acme"));

        assertEquals("support-bot-acme", def.metadata().name());
        assertEquals("acme", def.metadata().tenant());
        // brandName not provided -> default; tenantId provided -> param value
        assertEquals("你是 七七商城 的客服助手，租户 acme。", def.spec().persona().systemPrompt());
        // Non-string values pass through untouched
        assertEquals(0.3, def.spec().persona().temperature());
        assertEquals("order-query", def.spec().tools().get(0).ref());
    }

    @Test
    void providedParamBeatsDefault() {
        AgentDefinition def = AgentTemplate.parse(TEMPLATE_YAML)
                .instantiate("bot", null, Map.of("tenantId", "t1", "brandName", "旗舰店"));

        assertEquals("你是 旗舰店 的客服助手，租户 t1。", def.spec().persona().systemPrompt());
    }

    @Test
    void placeholderInStructuralFieldsWorksToo() {
        // Substitution is a generic tree transform - it works on ANY string leaf,
        // including fields the template author invents later (future schema sections).
        AgentDefinition def = AgentTemplate.parse("""
                apiVersion: v1
                kind: AgentTemplate
                metadata:
                  name: x
                variables:
                  - name: tenantId
                spec:
                  persona:
                    systemPrompt: "hi ${tenantId}"
                  model:
                    provider: model-${tenantId}
                """).instantiate("bot", null, Map.of("tenantId", "acme"));

        assertEquals("model-acme", def.spec().model().provider());
    }

    @Test
    void missingRequiredParamIsRejectedWithVariableName() {
        AgentTemplate template = AgentTemplate.parse(TEMPLATE_YAML);

        DefinitionException e = assertThrows(DefinitionException.class,
                () -> template.instantiate("bot", null, Map.of()));
        assertTrue(e.getErrors().stream().anyMatch(err -> err.path().equals("params.tenantId")),
                () -> "error should name the missing variable, got: " + e.getErrors());
    }

    @Test
    void undeclaredParamIsRejected() {
        AgentTemplate template = AgentTemplate.parse(TEMPLATE_YAML);

        DefinitionException e = assertThrows(DefinitionException.class,
                () -> template.instantiate("bot", null, Map.of("tenantId", "t", "extra", "x")));
        assertTrue(e.getErrors().stream().anyMatch(err -> err.path().equals("params.extra")),
                () -> "undeclared param should be named, got: " + e.getErrors());
    }

    @Test
    void blankInstanceNameRejected() {
        AgentTemplate template = AgentTemplate.parse(TEMPLATE_YAML);
        assertThrows(IllegalArgumentException.class,
                () -> template.instantiate(" ", null, Map.of("tenantId", "t")));
    }

    // ============ D6: fork snapshot ============

    @Test
    void instantiationIsAForkSnapshotNotALiveView() {
        AgentTemplate v1 = AgentTemplate.parse(TEMPLATE_YAML);

        AgentDefinition before = v1.instantiate("bot", null, Map.of("tenantId", "t"));

        // Upgrade the template (same name, different prompt).
        AgentTemplate v2 = AgentTemplate.parse(TEMPLATE_YAML.replace("客服助手", "售后助手"));
        assertEquals("support-agent", v2.metadata().name());

        AgentDefinition after = v2.instantiate("bot", null, Map.of("tenantId", "t"));

        // The earlier instance is unaffected by the template change (D6).
        assertEquals("你是 七七商城 的客服助手，租户 t。", before.spec().persona().systemPrompt());
        assertEquals("你是 七七商城 的售后助手，租户 t。", after.spec().persona().systemPrompt());
        assertNotEquals(before.spec().persona().systemPrompt(), after.spec().persona().systemPrompt());
    }

    @Test
    void repeatedInstantiationYieldsIndependentDefinitions() {
        AgentTemplate template = AgentTemplate.parse(TEMPLATE_YAML);
        Map<String, String> params = Map.of("tenantId", "t");

        AgentDefinition a = template.instantiate("bot-1", null, params);
        AgentDefinition b = template.instantiate("bot-2", null, params);

        // Same spec content, different instance names - and no shared mutable state.
        assertEquals(a.spec(), b.spec());
        assertEquals("bot-1", a.metadata().name());
        assertEquals("bot-2", b.metadata().name());
    }

    @Test
    void templateWithoutVariablesNeedsEmptyParams() {
        AgentDefinition def = AgentTemplate.parse("""
                apiVersion: v1
                kind: AgentTemplate
                metadata:
                  name: plain
                spec:
                  persona:
                    systemPrompt: "I have no variables."
                  model:
                    provider: openai
                """).instantiate("plain-bot", null, Map.of());

        assertEquals("I have no variables.", def.spec().persona().systemPrompt());
        assertNull(def.metadata().tenant());
    }
}
