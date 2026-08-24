package io.github.qwzhang01.agent.enterprise.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.memory.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 15 M15.2: the model-facing knowledge retrieval tool.
 * <p>
 * The contract under test: the tenant binding is immutable and invisible to
 * the model - even with fully attacker-controlled arguments the tool cannot
 * be pointed at another tenant's knowledge.
 */
class KnowledgeToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KnowledgeBase knowledge;
    private KnowledgeTool acmeTool;

    @BeforeEach
    void setUp() throws Exception {
        knowledge = new KnowledgeBase(new InMemoryMemoryStore());
        knowledge.ingest("acme", List.of(
                KnowledgeEntry.of("Return Policy", "acme: 30-day no-question returns"),
                KnowledgeEntry.of("Invoice Policy", "acme: invoices within 24 hours"),
                KnowledgeEntry.of("Shipping", "acme: free shipping over 99"),
                KnowledgeEntry.of("Warranty", "acme: 2-year warranty on electronics"),
                KnowledgeEntry.of("Refunds", "acme: refunds to original payment method")
        ), "admin");
        knowledge.ingest("globex", List.of(
                KnowledgeEntry.of("Return Policy", "globex: all sales final, no returns")
        ), "admin");
        acmeTool = KnowledgeTool.forTenant(knowledge, "acme");
    }

    private JsonNode executeAsJson(KnowledgeTool tool, String jsonArgs) throws Exception {
        String raw = tool.execute(MAPPER.readTree(jsonArgs));
        return MAPPER.readTree(raw);
    }

    // ============ Output Contract ============

    @Test
    @DisplayName("execute returns well-formed JSON with count and results")
    void jsonOutputContract() throws Exception {
        JsonNode root = executeAsJson(acmeTool, "{\"query\": \"returns\"}");

        assertEquals(1, root.get("count").asInt());
        JsonNode results = root.get("results");
        assertTrue(results.isArray());
        assertEquals("Return Policy", results.get(0).get("title").asText());
        assertTrue(results.get(0).get("content").asText().contains("30-day"));
    }

    @Test
    @DisplayName("no-hit search returns count 0 with an honest empty array")
    void noHitSearch() throws Exception {
        JsonNode root = executeAsJson(acmeTool, "{\"query\": \"quantum-thermodynamics\"}");

        assertEquals(0, root.get("count").asInt());
        assertEquals(0, root.get("results").size());
        assertTrue(root.get("message").asText().contains("no knowledge found"));
    }

    @Test
    @DisplayName("top_k argument limits the result count")
    void topKArgument() throws Exception {
        knowledge.ingest("acme", List.of(
                KnowledgeEntry.of("Extra 1", "matching keyword xyz"),
                KnowledgeEntry.of("Extra 2", "matching keyword xyz"),
                KnowledgeEntry.of("Extra 3", "matching keyword xyz"),
                KnowledgeEntry.of("Extra 4", "matching keyword xyz")
        ), "admin");

        JsonNode limited = executeAsJson(acmeTool, "{\"query\": \"xyz\", \"top_k\": 2}");
        JsonNode unlimited = executeAsJson(acmeTool, "{\"query\": \"xyz\"}");

        assertEquals(2, limited.get("count").asInt());
        assertEquals(KnowledgeEntry.DEFAULT_TOP_K, unlimited.get("count").asInt());
    }

    // ============ Tenant Binding ============

    @Test
    @DisplayName("the tool cannot be pointed at another tenant - binding is immutable")
    void tenantBindingImmutable() throws Exception {
        KnowledgeTool globexTool = KnowledgeTool.forTenant(knowledge, "globex");

        // even arguments that try to specify a tenant have no such parameter
        JsonNode fromAcme = executeAsJson(acmeTool, "{\"query\": \"return\", \"tenant\": \"globex\"}");
        JsonNode fromGlobex = executeAsJson(globexTool, "{\"query\": \"return\", \"tenant\": \"acme\"}");

        assertEquals("acme: 30-day no-question returns",
                fromAcme.get("results").get(0).get("content").asText());
        assertEquals("globex: all sales final, no returns",
                fromGlobex.get("results").get(0).get("content").asText());
    }

    @Test
    @DisplayName("bound tenant id is visible for assembly/audit but never in the schema")
    void boundTenantVisibleForAudit() {
        assertEquals("acme", acmeTool.boundTenantId());
        String schema = acmeTool.getParametersSchema();
        assertTrue(!schema.contains("tenant"), "schema must not expose a tenant parameter");
    }

    // ============ Argument Validation ============

    @Test
    @DisplayName("missing or blank query fails fast with ToolException")
    void missingQueryRejected() {
        assertThrows(ToolException.class, () -> acmeTool.execute(null));
        assertThrows(ToolException.class, () -> acmeTool.execute(MAPPER.createObjectNode()));
        assertThrows(ToolException.class,
                () -> acmeTool.execute(MAPPER.readTree("{\"query\": \"  \"}")));
    }

    // ============ Tool Registration Contract ============

    @Test
    @DisplayName("tool metadata is complete for registration")
    void toolMetadata() {
        assertEquals(KnowledgeTool.NAME, acmeTool.getName());
        assertTrue(acmeTool.getName().equals("search_knowledge"));
        assertNotNull(acmeTool.getDescription());
        assertNotNull(acmeTool.getParametersSchema());
        assertTrue(acmeTool.getDescription().toLowerCase().contains("knowledge"));
    }
}
