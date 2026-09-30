package io.github.qwzhang01.agent.rag.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Restriction applied inside index search, so {@code topK} is filled with matching chunks only.
 * <p>
 * Two independent parts, both must match:
 * <ul>
 *   <li>{@link #metadata()}: exact-match metadata filters; empty for none.</li>
 *   <li>{@link #docIdPrefixes()}: allowed document id prefixes. Absent means no restriction;
 *       present but empty means nothing is allowed ({@link #matchesNothing()}), and searches
 *       return an empty list without querying the index.</li>
 * </ul>
 * Prefixes are plain string prefixes of the document id (for {@link
 * io.github.qwzhang01.agent.rag.index.IncrementalIndexer} the root-relative path, e.g.
 * {@code team-a/} allows {@code team-a/guide.md}). No wildcard syntax: {@code %}, {@code _},
 * {@code *} are literal characters. A prefix already covered by a shorter one in the set is dropped,
 * and the empty prefix allows every document.
 * <p>
 * Immutable and thread-safe.
 */
public final class SearchFilter {

    private static final SearchFilter NONE = new SearchFilter(Map.of(), null);

    private final Map<String, String> metadata;
    private final Set<String> docIdPrefixes;

    private SearchFilter(Map<String, String> metadata, Set<String> docIdPrefixes) {
        this.metadata = metadata;
        this.docIdPrefixes = docIdPrefixes;
    }

    /** No metadata filter and no document restriction. */
    public static SearchFilter none() {
        return NONE;
    }

    /** Metadata filters only; null or empty means none. Null keys or values are rejected. */
    public static SearchFilter of(Map<String, String> metadata) {
        return metadata == null || metadata.isEmpty() ? NONE : new SearchFilter(copyMetadata(metadata), null);
    }

    /** Copy with the metadata filters replaced; null means none. */
    public SearchFilter withMetadata(Map<String, String> metadata) {
        Map<String, String> copy = metadata == null ? Map.of() : copyMetadata(metadata);
        return copy.isEmpty() && docIdPrefixes == null ? NONE : new SearchFilter(copy, docIdPrefixes);
    }

    /**
     * Copy restricted to documents whose id starts with one of {@code prefixes}.
     *
     * @param prefixes allowed prefixes; null removes the restriction, an empty collection allows nothing
     */
    public SearchFilter withDocIdPrefixes(Collection<String> prefixes) {
        if (prefixes == null) {
            return metadata.isEmpty() ? NONE : new SearchFilter(metadata, null);
        }
        return new SearchFilter(metadata, minimalPrefixes(prefixes));
    }

    /** Exact-match metadata filters, possibly empty. */
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * Allowed document id prefixes, sorted; empty {@link Optional} when document ids are not restricted.
     */
    public Optional<Set<String>> docIdPrefixes() {
        return Optional.ofNullable(docIdPrefixes);
    }

    /** True when document ids are restricted to {@link #docIdPrefixes()}. */
    public boolean restrictsDocIds() {
        return docIdPrefixes != null;
    }

    /** True when the allowed prefix set is present and empty: no chunk can match. */
    public boolean matchesNothing() {
        return docIdPrefixes != null && docIdPrefixes.isEmpty();
    }

    /** True when neither metadata nor document ids are restricted. */
    public boolean isUnrestricted() {
        return metadata.isEmpty() && docIdPrefixes == null;
    }

    /** Whether {@code docId} passes the document restriction (metadata is not checked). */
    public boolean allowsDocId(String docId) {
        Objects.requireNonNull(docId, "docId");
        if (docIdPrefixes == null) {
            return true;
        }
        for (String prefix : docIdPrefixes) {
            if (docId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a chunk passes both parts of the filter. */
    public boolean allows(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (!allowsDocId(chunk.docId())) {
            return false;
        }
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            if (!e.getValue().equals(chunk.metadata().get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SearchFilter other
                && metadata.equals(other.metadata)
                && Objects.equals(docIdPrefixes, other.docIdPrefixes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadata, docIdPrefixes);
    }

    @Override
    public String toString() {
        return "SearchFilter[metadata=" + metadata + ", docIdPrefixes="
                + (docIdPrefixes == null ? "<unrestricted>" : docIdPrefixes) + "]";
    }

    private static Map<String, String> copyMetadata(Map<String, String> metadata) {
        metadata.forEach((k, v) -> {
            Objects.requireNonNull(k, "filter key");
            Objects.requireNonNull(v, "filter value");
        });
        return Map.copyOf(metadata);
    }

    /** Sorted, deduplicated, without prefixes already covered by a shorter prefix in the set. */
    private static Set<String> minimalPrefixes(Collection<String> prefixes) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String prefix : prefixes) {
            sorted.add(Objects.requireNonNull(prefix, "docId prefix"));
        }
        // In sorted order every string starting with p directly follows p, so comparing with the
        // last kept prefix is enough.
        List<String> kept = new ArrayList<>(sorted.size());
        for (String prefix : sorted) {
            if (kept.isEmpty() || !prefix.startsWith(kept.get(kept.size() - 1))) {
                kept.add(prefix);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(kept));
    }
}
