package io.github.qwzhang01.agent.product.definition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.1 parser tests: legal YAML/JSON both parse to the same definition; syntax
 * errors and unknown fields fail fast with location information.
 */
class AgentDefinitionParserTest {

    private final AgentDefinitionParser parser = new AgentDefinitionParser();

    // ============ Fixtures ============

    private static final String VALID_YAML = """
            apiVersion: v1
            kind: Agent
            metadata:
              name: support-bot
              tenant: acme
            spec:
              persona:
                systemPrompt: "You are a support assistant."
                temperature: 0.3
              model:
                provider: openai
                fallback: deepseek
              tools:
                - ref: order-query
                - ref: refund-search
              memory:
                shortTerm:
                  strategy: window
                  maxMessages: 20
            """;

    /** Same definition, JSON syntax (YAML is a JSON superset - one mapper handles both). */
    private static final String SAME_AS_JSON = """
            {
              "apiVersion": "v1",
              "kind": "Agent",
              "metadata": {"name": "support-bot", "tenant": "acme"},
              "spec": {
                "persona": {"systemPrompt": "You are a support assistant.", "temperature": 0.3},
                "model": {"provider": "openai", "fallback": "deepseek"},
                "tools": [{"ref": "order-query"}, {"ref": "refund-search"}],
                "memory": {"shortTerm": {"strategy": "window", "maxMessages": 20}}
              }
            }
            """;

    // ============ Legal input ============

    @Test
    void parsesFullYamlDefinition() {
        AgentDefinition def = parser.parse(VALID_YAML);

        assertEquals("support-bot", def.metadata().name());
        assertEquals("acme", def.metadata().tenant());
        assertEquals("You are a support assistant.", def.spec().persona().systemPrompt());
        assertEquals(0.3, def.spec().persona().temperature());
        assertEquals("openai", def.spec().model().provider());
        assertEquals("deepseek", def.spec().model().fallback());
        assertEquals(2, def.spec().tools().size());
        assertEquals("order-query", def.spec().tools().get(0).ref());
        assertEquals("window", def.spec().memory().shortTerm().strategy());
        assertEquals(20, def.spec().memory().shortTerm().maxMessages());
    }

    @Test
    void jsonSourceParsesToSameDefinition() {
        AgentDefinition fromYaml = parser.parse(VALID_YAML);
        AgentDefinition fromJson = parser.parse(SAME_AS_JSON);

        assertEquals(fromYaml, fromJson); // record equality
    }

    @Test
    void minimalDefinitionWithoutOptionalSections() {
        AgentDefinition def = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: bare-bot
                spec:
                  persona:
                    systemPrompt: "Hello."
                  model:
                    provider: mock
                """);

        assertEquals("bare-bot", def.metadata().name());
        assertEquals(null, def.metadata().tenant());
        assertEquals(null, def.spec().tools());          // no tools is legal
        assertEquals(null, def.spec().memory());         // passthrough is legal
        assertEquals(null, def.spec().persona().temperature());
        assertEquals(null, def.spec().model().fallback());
    }

    // ============ Fail-fast: structure ============

    @Test
    void blankNameIsRejected() {
        DefinitionException e = assertThrows(DefinitionException.class, () -> parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: ""
                spec:
                  persona:
                    systemPrompt: "x"
                  model:
                    provider: mock
                """));
        assertNotNull(e.getMessage());
    }

    @Test
    void yamlSyntaxErrorCarriesLocation() {
        DefinitionException e = assertThrows(DefinitionException.class, () ->
                parser.parse("""
                        metadata:
                          name: broken
                          [unclosed
                        """));
        assertTrue(e.getMessage().contains("line"), "message should carry a location, got: " + e.getMessage());
    }

    // ============ Fail-fast: envelope ============

    @Test
    void wrongApiVersionIsRejected() {
        DefinitionException e = assertThrows(DefinitionException.class, () -> parser.parse("""
                apiVersion: v2
                kind: Agent
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: m
                """));
        assertTrue(e.getMessage().contains("v1"), e.getMessage());
    }

    @Test
    void wrongKindIsRejected() {
        DefinitionException e = assertThrows(DefinitionException.class, () -> parser.parse("""
                apiVersion: v1
                kind: Workflow
                metadata:
                  name: x
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: m
                """));
        assertTrue(e.getMessage().contains("Agent"), e.getMessage());
    }

    // ============ Fail-fast: unknown fields ============

    @Test
    void unknownLaterMilestoneFieldGetsTargetedHint() {
        DefinitionException e = assertThrows(DefinitionException.class, () -> parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: future-bot
                  template: support-agent@1.2
                spec:
                  persona:
                    systemPrompt: "x"
                  model:
                    provider: mock
                """));
        assertTrue(e.getMessage().contains("template"));
        assertTrue(e.getMessage().contains("instantiate"),
                "planned-field errors should point at the workaround, got: " + e.getMessage());
    }

    @Test
    void typoFieldIsRejectedAsUnknown() {
        DefinitionException e = assertThrows(DefinitionException.class, () -> parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: typo-bot
                spec:
                  persona:
                    systemprompt: "x"   # wrong case
                  model:
                    provider: mock
                """));
        assertTrue(e.getMessage().contains("systemprompt"),
                "message should name the unknown field, got: " + e.getMessage());
    }

    @Test
    void unknownTopLevelFieldIsRejected() {
        assertThrows(DefinitionException.class, () -> parser.parse("""
                apiVersion: v1
                kind: Agent
                totally-unknown: 1
                metadata:
                  name: x-bot
                spec:
                  persona:
                    systemPrompt: "x"
                  model:
                    provider: mock
                """));
    }

    // ============ Defensive copy ============

    @Test
    void toolsListIsDefensivelyCopied() {
        AgentDefinition def = parser.parse(VALID_YAML);
        List<AgentDefinition.ToolRef> tools = def.spec().tools();
        assertThrows(UnsupportedOperationException.class, () -> tools.add(new AgentDefinition.ToolRef("x")));
    }

    // ============ M13.3: inline http tool entries parse ============

    @Test
    void parsesInlineHttpToolEntry() {
        AgentDefinition def = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: http-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  tools:
                    - ref: order-query
                    - http:
                        name: weather-query
                        description: 查询城市实时天气
                        endpoint: https://api.example.com/v1/now
                        method: GET
                        params:
                          city: { in: query, type: string, required: true }
                        response:
                          extract: "$.data.temperature"
                        auth:
                          type: bearer
                          token: "${env:WEATHER_TOKEN}"
                        timeoutSeconds: 3
                """);

        assertEquals(2, def.spec().tools().size());
        assertEquals("order-query", def.spec().tools().get(0).ref());
        var http = def.spec().tools().get(1).http();
        assertEquals("weather-query", http.name());
        assertEquals("https://api.example.com/v1/now", http.endpoint());
        assertEquals("GET", http.method());
        assertEquals("query", http.params().get("city").in());
        assertTrue(http.params().get("city").required());
        assertEquals("$.data.temperature", http.response().extract());
        assertEquals("bearer", http.auth().type());
        assertEquals("${env:WEATHER_TOKEN}", http.auth().token());
        assertEquals(3, http.timeoutSeconds());
        // The shared namespace helper sees the http name.
        assertEquals("weather-query", def.spec().tools().get(1).toolName());
    }

    @Test
    void toolEntryWithBothRefAndHttpIsRejected() {
        DefinitionException e = assertThrows(DefinitionException.class, () -> parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: both-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: openai
                  tools:
                    - ref: order-query
                      http:
                        name: x
                        description: d
                        endpoint: https://a.b/c
                """));
        assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
    }

    // ============ M13.4: promptRef parses ============

    @Test
    void parsesPersonaWithPromptRef() {
        AgentDefinition def = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: managed-bot
                  tenant: acme
                spec:
                  persona:
                    promptRef: { name: support-system, channel: canary }
                    temperature: 0.3
                  model:
                    provider: openai
                """);

        assertEquals("support-system", def.spec().persona().promptRef().name());
        assertEquals("canary", def.spec().persona().promptRef().channel());
        assertEquals(null, def.spec().persona().systemPrompt());
        assertEquals(0.3, def.spec().persona().temperature());
    }

    @Test
    void promptRefWithoutChannelParsesAsNull() {
        AgentDefinition def = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: m
                spec:
                  persona:
                    promptRef: { name: support-system }
                  model:
                    provider: openai
                """);
        assertEquals(null, def.spec().persona().promptRef().channel());
    }

    // ============ M13.5: workflow + ambient sections parse ============

    @Test
    void parsesWorkflowAndAmbientSections() {
        AgentDefinition def = parser.parse("""
                apiVersion: v1
                kind: Agent
                metadata:
                  name: ops-bot
                spec:
                  persona:
                    systemPrompt: "你是运维助手。"
                  model:
                    provider: openai
                  workflow: support-flow
                  ambient:
                    - instructionId: ticket-watch
                      description: 工单提醒
                      trigger: { onEvent: "ticket-updated" }
                      importance: WARN
                      messageTemplate: "工单 {$.ticketId} 变化"
                    - instructionId: heartbeat
                      description: 心跳
                      trigger: { schedule: "PT10M" }
                      importance: INFO
                      messageTemplate: "例行检查"
                """);

        assertEquals("support-flow", def.spec().workflow());
        assertEquals(2, def.spec().ambient().size());
        assertEquals("ticket-watch", def.spec().ambient().get(0).instructionId());
        assertEquals("ticket-updated", def.spec().ambient().get(0).trigger().onEvent());
        assertEquals("PT10M", def.spec().ambient().get(1).trigger().schedule());
        assertEquals("WARN", def.spec().ambient().get(0).importance());
    }
}
