package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness batch 3: McpServerCapabilities parsing semantics.
 * <p>
 * The asymmetry under test: a real declaration that omits tools is a
 * server that scoped itself away from tools (the tool path must refuse
 * it), while a non-declaration (null) is honored as the tools-only era
 * the framework has always served (2024-11-05 optional-field semantics).
 */
class McpServerCapabilitiesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fullDeclarationParsesEveryFlag() {
        var caps = McpServerCapabilities.from(parse(
                "{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},"
                        + "\"capabilities\":{"
                        + "\"tools\":{\"listChanged\":true},"
                        + "\"resources\":{},"
                        + "\"prompts\":{},"
                        + "\"logging\":{}}}"));

        assertNotNull(caps);
        assertTrue(caps.tools());
        assertTrue(caps.resources());
        assertTrue(caps.prompts());
        assertTrue(caps.logging());
    }

    @Test
    void toolsOnlyDeclarationIsToolsCapable() {
        var caps = McpServerCapabilities.from(parse(
                "{\"capabilities\":{\"tools\":{\"listChanged\":true}}}"));

        assertNotNull(caps);
        assertTrue(caps.tools());
        assertFalse(caps.resources());
        assertFalse(caps.prompts());
        assertFalse(caps.logging());
    }

    @Test
    void declarationWithoutToolsExcludesTheToolPath() {
        var caps = McpServerCapabilities.from(parse(
                "{\"capabilities\":{\"resources\":{},\"prompts\":{}}}"));

        assertNotNull(caps);
        assertFalse(caps.tools());
    }

    @Test
    void loggingIsAPlainBoolean() {
        var caps = McpServerCapabilities.from(parse(
                "{\"capabilities\":{\"tools\":{},\"logging\":true}}"));

        assertNotNull(caps);
        assertTrue(caps.logging());
    }

    @Test
    void absentCapabilitiesMeansNoDeclaration() {
        // 2024-11-05 optional-field semantics: old-era servers send no
        // capabilities object at all and are still valid tools servers.
        assertNull(McpServerCapabilities.from(parse("{\"serverInfo\":{\"name\":\"s\"}}")));
    }

    @Test
    void emptyCapabilitiesObjectMeansNoDeclaration() {
        assertNull(McpServerCapabilities.from(parse("{\"capabilities\":{}}")));
    }

    @Test
    void nullResultMeansNoDeclaration() {
        assertNull(McpServerCapabilities.from(null));
    }

    @Test
    void nonObjectCapabilitiesMeansNoDeclaration() {
        assertNull(McpServerCapabilities.from(parse("{\"capabilities\":\"garbage\"}")));
        assertNull(McpServerCapabilities.from(parse("{\"capabilities\":[1,2]}")));
    }

    @Test
    void toStringCarriesAllFourFlagsForLogs() {
        var caps = McpServerCapabilities.from(parse(
                "{\"capabilities\":{\"tools\":{},\"logging\":true}}"));
        String s = caps.toString();
        assertTrue(s.contains("tools=true"));
        assertTrue(s.contains("logging=true"));
        assertTrue(s.contains("resources=false"));
    }
}
