package io.github.qwzhang01.agent.rag.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A question to answer, with optional conversation history and metadata filters.
 *
 * @param question the current user question, verbatim
 * @param history  prior turns, oldest first; drives query rewriting
 * @param filters  exact-match metadata filters applied at retrieval (e.g. {@code version=v2})
 */
public record RagQuery(String question, List<ConversationTurn> history, Map<String, String> filters) {

    public RagQuery {
        Objects.requireNonNull(question, "question");
        history = history == null ? List.of() : List.copyOf(history);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    public static RagQuery of(String question) {
        return new RagQuery(question, List.of(), Map.of());
    }
}
