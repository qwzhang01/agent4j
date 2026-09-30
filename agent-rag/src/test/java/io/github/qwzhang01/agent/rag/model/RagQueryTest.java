package io.github.qwzhang01.agent.rag.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagQueryTest {

    @Test
    void threeArgumentConstructorKeepsMetadataOnly() {
        RagQuery q = new RagQuery("q", null, Map.of("version", "2"));

        assertEquals(Map.of("version", "2"), q.filters());
        assertEquals(SearchFilter.of(Map.of("version", "2")), q.searchFilter());
        assertFalse(q.searchFilter().restrictsDocIds());
        assertEquals(List.of(), q.history());
        assertEquals(SearchFilter.none(), RagQuery.of("q").searchFilter());
    }

    @Test
    void searchFilterAndFiltersStayConsistent() {
        SearchFilter access = SearchFilter.none().withDocIdPrefixes(List.of("team-a/"));

        RagQuery merged = new RagQuery("q", List.of(), Map.of("version", "2"), access);
        RagQuery fromFilter = RagQuery.of("q", access.withMetadata(Map.of("lang", "zh")));

        assertEquals(Map.of("version", "2"), merged.filters());
        assertEquals(Optional.of(Set.of("team-a/")), merged.searchFilter().docIdPrefixes());
        assertEquals(Map.of("version", "2"), merged.searchFilter().metadata());
        assertEquals(Map.of("lang", "zh"), fromFilter.filters());
        assertEquals(merged, new RagQuery("q", List.of(), Map.of("version", "2"),
                SearchFilter.of(Map.of("version", "2")).withDocIdPrefixes(List.of("team-a/"))));
        assertThrows(IllegalArgumentException.class, () -> new RagQuery("q", List.of(), Map.of("version", "2"),
                SearchFilter.of(Map.of("version", "3"))));
    }

    @Test
    void withersReplaceTheRestriction() {
        RagQuery q = new RagQuery("q", List.of(ConversationTurn.user("h")), Map.of("version", "2"));

        RagQuery restricted = q.withDocIdPrefixes(List.of());
        RagQuery reopened = restricted.withDocIdPrefixes(null);

        assertTrue(restricted.searchFilter().matchesNothing());
        assertEquals(Map.of("version", "2"), restricted.filters());
        assertEquals(q.history(), restricted.history());
        assertEquals(q, reopened);
        assertEquals(Map.of(), q.withSearchFilter(null).filters());
    }
}
