package io.github.qwzhang01.agent.memory.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.memory.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tools that let the model self-manage memory (Stage 8 D8).
 * <p>
 * Two tools, mirroring the Stage 3 self-evolution pattern (agent manages its own
 * capabilities). Here the agent manages its own memory:
 * <ul>
 *   <li>{@code save_memory} - explicitly store a fact/preference (importance=1.0,
 *       bypasses the policy threshold - D6 gate 1 exemption)</li>
 *   <li>{@code search_memory} - query visible memories by keyword</li>
 * </ul>
 * <p>
 * The model decides what's worth remembering and what to recall - this is the
 * "model self-decided storage" path described in D8.
 */
public class MemoryTools {

    private MemoryTools() {
    }

    /**
     * Create a save_memory tool bound to a store + scope + actor.
     *
     * @param store     the memory store
     * @param scope     the scope to save under
     * @param policy    the write-gate policy (for default status + supersede)
     * @param actorId   who is saving (model id / user id)
     */
    public static Tool saveMemory(MemoryStore store, String scope, MemoryPolicy policy, String actorId) {
        return new Tool() {
            private final ObjectMapper mapper = new ObjectMapper();

            @Override
            public String getName() {
                return "save_memory";
            }

            @Override
            public String getDescription() {
                return "Save a fact or preference to long-term memory. Use this when the user states "
                        + "something worth remembering for future conversations. "
                        + "The saved memory will be recalled automatically in future turns.";
            }

            @Override
            public String getParametersSchema() {
                return """
                        {
                          "type": "object",
                          "properties": {
                            "subject": { "type": "string", "description": "Topic key, e.g. 'dietary-restriction'" },
                            "content": { "type": "string", "description": "The fact or preference to remember" },
                            "type": { "type": "string", "enum": ["PREFERENCE","FACT","EVENT"], "description": "Memory type (default: FACT)" }
                          },
                          "required": ["subject", "content"]
                        }""";
            }

            @Override
            public String execute(JsonNode arguments) throws ToolException {
                try {
                    String subject = arguments.get("subject").asText();
                    String content = arguments.get("content").asText();
                    String typeStr = arguments.has("type") ? arguments.get("type").asText() : "FACT";
                    MemoryType type = MemoryType.valueOf(typeStr.toUpperCase());

                    // importance=1.0 -> explicit save bypasses threshold (D8)
                    MemoryEntry candidate = new MemoryEntry(
                            null, scope, type, subject, content, 1.0,
                            MemoryProvenance.modelDerived(actorId, null, Instant.now()),
                            MemoryStatus.ACTIVE, Instant.now(), null
                    );

                    // Apply policy: supersede if same subject has different content
                    if (policy.shouldSupersede(candidate, store)) {
                        store.findActiveBySubject(scope, subject)
                                .ifPresent(old -> store.update(old.withStatus(MemoryStatus.SUPERSEDED)));
                    }

                    // Apply default status (channel -> PENDING_REVIEW)
                    MemoryStatus status = policy.defaultStatusForScope(scope);
                    MemoryEntry stored = store.write(candidate.withStatus(status));

                    return "Saved memory [" + type + "] subject='" + subject + "' (status=" + status + ")";
                } catch (Exception e) {
                    throw new ToolException("Failed to save memory: " + e.getMessage(), e);
                }
            }
        };
    }

    /**
     * Create a search_memory tool bound to a retriever + visible scopes.
     *
     * @param retriever the memory retriever
     * @param scopes    the scopes visible to this agent
     */
    public static Tool searchMemory(MemoryRetriever retriever, List<String> scopes) {
        return new Tool() {
            @Override
            public String getName() {
                return "search_memory";
            }

            @Override
            public String getDescription() {
                return "Search long-term memories by keyword. Returns active memories visible "
                        + "in the current context. Use this to recall what was previously saved.";
            }

            @Override
            public String getParametersSchema() {
                return """
                        {
                          "type": "object",
                          "properties": {
                            "keyword": { "type": "string", "description": "Search keyword (matches memory content)" }
                          },
                          "required": ["keyword"]
                        }""";
            }

            @Override
            public String execute(JsonNode arguments) throws ToolException {
                try {
                    String keyword = arguments.get("keyword").asText();
                    List<MemoryEntry> results = retriever.recallByKeyword(scopes, keyword);

                    if (results.isEmpty()) {
                        return "No memories found for keyword: " + keyword;
                    }
                    return results.stream()
                            .map(e -> "- [" + e.type() + "] " + e.subject() + ": " + e.content())
                            .collect(Collectors.joining("\n", "Found " + results.size() + " memories:\n", ""));
                } catch (Exception e) {
                    throw new ToolException("Failed to search memory: " + e.getMessage(), e);
                }
            }
        };
    }
}
