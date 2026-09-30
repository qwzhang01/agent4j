package io.github.qwzhang01.agent.rag.model;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A question to answer, with optional conversation history and retrieval restrictions.
 * <p>
 * {@code filters} and {@code searchFilter} describe the same restriction and are kept consistent:
 * {@code filters} always equals {@code searchFilter.metadata()}. When both are given with
 * non-empty metadata they must be equal.
 *
 * @param question     the current user question, verbatim
 * @param history      prior turns, oldest first; drives query rewriting
 * @param filters      exact-match metadata filters applied at retrieval (e.g. {@code version=v2})
 * @param searchFilter full retrieval restriction, including allowed document-id prefixes; null
 *                     means metadata filters only
 */
public record RagQuery(String question, List<ConversationTurn> history, Map<String, String> filters,
                       SearchFilter searchFilter) {

    public RagQuery {
        Objects.requireNonNull(question, "question");
        history = history == null ? List.of() : List.copyOf(history);
        Map<String, String> metadata = filters == null ? Map.of() : Map.copyOf(filters);
        SearchFilter base = searchFilter == null ? SearchFilter.none() : searchFilter;
        if (!metadata.isEmpty() && !base.metadata().isEmpty() && !metadata.equals(base.metadata())) {
            throw new IllegalArgumentException("filters " + metadata
                    + " conflict with searchFilter metadata " + base.metadata());
        }
        if (base.metadata().isEmpty()) {
            base = base.withMetadata(metadata);
        }
        searchFilter = base;
        filters = base.metadata();
    }

    /** Query without document-id restriction. */
    public RagQuery(String question, List<ConversationTurn> history, Map<String, String> filters) {
        this(question, history, filters, null);
    }

    public static RagQuery of(String question) {
        return new RagQuery(question, List.of(), Map.of(), null);
    }

    public static RagQuery of(String question, SearchFilter searchFilter) {
        return new RagQuery(question, List.of(), Map.of(), searchFilter);
    }

    /** Copy with {@link #searchFilter()} replaced; {@link #filters()} follows its metadata. */
    public RagQuery withSearchFilter(SearchFilter searchFilter) {
        return new RagQuery(question, history, Map.of(), searchFilter);
    }

    /**
     * Copy restricted to documents whose id starts with one of {@code prefixes}; see
     * {@link SearchFilter#withDocIdPrefixes}. Null removes the restriction, empty allows nothing.
     */
    public RagQuery withDocIdPrefixes(Collection<String> prefixes) {
        return withSearchFilter(searchFilter.withDocIdPrefixes(prefixes));
    }
}
