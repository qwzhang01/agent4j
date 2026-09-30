package io.github.qwzhang01.agent.rag.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchFilterTest {

    private static Chunk chunk(String docId, Map<String, String> metadata) {
        return new Chunk(docId + "#0", docId, null, null, List.of(), "", 0, 0, null, metadata);
    }

    @Test
    void noneIsUnrestricted() {
        SearchFilter none = SearchFilter.none();

        assertTrue(none.isUnrestricted());
        assertFalse(none.restrictsDocIds());
        assertFalse(none.matchesNothing());
        assertEquals(Optional.empty(), none.docIdPrefixes());
        assertTrue(none.allowsDocId("anything"));
        assertSame(none, SearchFilter.of(null));
        assertSame(none, SearchFilter.of(Map.of()));
        assertSame(none, none.withDocIdPrefixes(null));
    }

    @Test
    void emptyAllowedSetMatchesNothingButNullDoesNotRestrict() {
        SearchFilter nothing = SearchFilter.none().withDocIdPrefixes(List.of());

        assertTrue(nothing.restrictsDocIds());
        assertTrue(nothing.matchesNothing());
        assertFalse(nothing.isUnrestricted());
        assertFalse(nothing.allowsDocId("a.md"));
        assertEquals(Optional.of(Set.of()), nothing.docIdPrefixes());
        assertFalse(nothing.withDocIdPrefixes(null).restrictsDocIds());
    }

    @Test
    void prefixesAreDeduplicatedSortedAndMinimal() {
        SearchFilter f = SearchFilter.none().withDocIdPrefixes(
                List.of("team-b/", "team-a/sub/", "team-a/", "team-a/", "team-ab/", "x"));

        assertEquals(List.of("team-a/", "team-ab/", "team-b/", "x"), List.copyOf(f.docIdPrefixes().orElseThrow()));
        assertTrue(f.allowsDocId("team-a/sub/deep.md"));
        assertTrue(f.allowsDocId("xyz.md"));
        assertFalse(f.allowsDocId("team-c/a.md"));
        assertEquals(List.of(""), List.copyOf(SearchFilter.none().withDocIdPrefixes(List.of("a/", "", "b/"))
                .docIdPrefixes().orElseThrow()));
    }

    @Test
    void metadataAndPrefixesCombine() {
        SearchFilter f = SearchFilter.of(Map.of("level", "public")).withDocIdPrefixes(List.of("team-a/"));

        assertEquals(Map.of("level", "public"), f.metadata());
        assertTrue(f.allows(chunk("team-a/x.md", Map.of("level", "public", "other", "1"))));
        assertFalse(f.allows(chunk("team-a/x.md", Map.of("level", "secret"))));
        assertFalse(f.allows(chunk("team-b/x.md", Map.of("level", "public"))));
        assertEquals(f, f.withMetadata(Map.of("level", "public")));
        assertEquals(Optional.of(Set.of("team-a/")), f.withMetadata(null).docIdPrefixes());
        assertTrue(f.withMetadata(null).metadata().isEmpty());
        assertSame(SearchFilter.none(), SearchFilter.of(Map.of("k", "v")).withMetadata(Map.of()));
    }

    @Test
    void valueSemanticsAndImmutability() {
        Map<String, String> metadata = new HashMap<>(Map.of("k", "v"));
        SearchFilter a = SearchFilter.of(metadata).withDocIdPrefixes(List.of("p/"));
        metadata.put("k2", "v2");

        assertEquals(Map.of("k", "v"), a.metadata());
        assertEquals(a, SearchFilter.of(Map.of("k", "v")).withDocIdPrefixes(Set.of("p/")));
        assertEquals(a.hashCode(), SearchFilter.of(Map.of("k", "v")).withDocIdPrefixes(Set.of("p/")).hashCode());
        assertNotEquals(a, SearchFilter.of(Map.of("k", "v")));
        assertNotEquals(SearchFilter.none(), SearchFilter.none().withDocIdPrefixes(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> a.docIdPrefixes().orElseThrow().add("q/"));
        assertTrue(a.toString().contains("p/"));
        assertTrue(SearchFilter.none().toString().contains("unrestricted"));
    }

    @Test
    void nullKeysValuesAndPrefixesAreRejected() {
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("k", null);

        assertThrows(NullPointerException.class, () -> SearchFilter.of(nullValue));
        assertThrows(NullPointerException.class, () -> SearchFilter.none().withDocIdPrefixes(Arrays.asList("a", null)));
        assertThrows(NullPointerException.class, () -> SearchFilter.none().allowsDocId(null));
    }
}
