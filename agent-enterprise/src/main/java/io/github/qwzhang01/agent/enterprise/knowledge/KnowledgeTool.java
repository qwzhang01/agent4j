package io.github.qwzhang01.agent.enterprise.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;

import java.util.List;
import java.util.Objects;

/**
 * The model-facing knowledge retrieval tool: {@code search_knowledge}
 * (Stage 15 M15.2, D5).
 * <p>
 * The model decides WHEN to retrieve (blueprint D5: injection via tool call,
 * not via ContextBuilder pre-assembly - retrieval timing is a model decision).
 * What the model can NEVER decide is WHOSE knowledge to retrieve: the tenant
 * id is bound into the tool instance at assembly time and never appears in
 * the tool's parameters. Even a fully injected malicious prompt cannot make
 * this tool read another tenant's knowledge base - the scope whitelist holds
 * regardless of what the model asks for.
 * <p>
 * Output contract: a JSON object
 * {@code {"count": n, "results": [{"title": ..., "content": ...}]}} so the
 * model consumes structured slices it can cite; a no-hit search returns
 * {@code count: 0} with an empty array (honest empty, not an error).
 */
public final class KnowledgeTool implements Tool {

    /** Tool name registered with the model. */
    public static final String NAME = "search_knowledge";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeBase knowledgeBase;
    private final String tenantId;

    private KnowledgeTool(KnowledgeBase knowledgeBase, String tenantId) {
        this.knowledgeBase = Objects.requireNonNull(knowledgeBase, "knowledgeBase must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        this.tenantId = tenantId;
    }

    /**
     * Create the tool bound to one tenant's knowledge base. The binding is
     * immutable for the tool's lifetime - per-request assembly creates one
     * tool instance per request context (blueprint D2).
     */
    public static KnowledgeTool forTenant(KnowledgeBase knowledgeBase, String tenantId) {
        return new KnowledgeTool(knowledgeBase, tenantId);
    }

    // ============ Tool Contract ============

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Search the company knowledge base (policies, FAQs, product facts) by keyword. "
                + "Call this BEFORE answering factual questions about company rules or products, "
                + "and cite the retrieved titles in your answer.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string", "description": "Search keyword or phrase (matched against knowledge content)" },
                    "top_k": { "type": "integer", "description": "Max results to return (default 3)" }
                  },
                  "required": ["query"]
                }""";
    }

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        try {
            if (arguments == null || arguments.get("query") == null
                    || arguments.get("query").asText().isBlank()) {
                throw new ToolException("Missing required parameter 'query'");
            }
            String query = arguments.get("query").asText();
            int topK = arguments.has("top_k") && arguments.get("top_k").isInt()
                    ? arguments.get("top_k").asInt()
                    : KnowledgeEntry.DEFAULT_TOP_K;

            List<KnowledgeEntry> results = knowledgeBase.search(tenantId, query, topK);

            ObjectNode root = MAPPER.createObjectNode();
            root.put("count", results.size());
            ArrayNode array = root.putArray("results");
            for (KnowledgeEntry entry : results) {
                ObjectNode item = array.addObject();
                item.put("title", entry.title());
                item.put("content", entry.content());
            }
            if (results.isEmpty()) {
                root.put("message", "no knowledge found for: " + query);
            }
            return MAPPER.writeValueAsString(root);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("Failed to search knowledge: " + e.getMessage(), e);
        }
    }

    // ============ Accessors ============

    /**
     * The tenant this tool is bound to (visible for assembly/audit purposes;
     * the model never sees this value).
     */
    public String boundTenantId() {
        return tenantId;
    }
}
