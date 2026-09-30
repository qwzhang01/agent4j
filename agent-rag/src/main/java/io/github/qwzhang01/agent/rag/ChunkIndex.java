package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SearchFilter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chunk storage with keyword and vector search. Documents are the unit of write:
 * {@link #upsert} replaces every chunk of a document atomically.
 * <p>
 * Implementations must be safe for concurrent reads during a write; readers see
 * either the old or the new version of a document, never a mix.
 * <p>
 * Filtering: the {@link SearchFilter} overloads apply metadata and document-id restrictions inside
 * the search, so {@code topK} counts allowed chunks only. Implementations that support document-id
 * restrictions override them; the defaults delegate metadata-only filters to the {@code Map}
 * overloads and throw {@link UnsupportedOperationException} for a document-id restriction rather
 * than return chunks the caller may not see.
 */
public interface ChunkIndex extends AutoCloseable {

    /**
     * Replace all chunks of {@code docId}.
     *
     * @param docId       document identity
     * @param contentHash hash of the source; returned later by {@link #contentHash}
     * @param chunks      chunks of the document, all with this {@code docId} and ids prefixed {@code docId + "#"}
     * @param vectors     one embedding per chunk, index-aligned; null entries allowed (keyword-only chunk)
     */
    void upsert(String docId, String contentHash, List<Chunk> chunks, List<float[]> vectors);

    /** Remove every chunk of {@code docId}; no-op when absent. */
    void delete(String docId);

    /** Content hash recorded at the last upsert of {@code docId}. */
    Optional<String> contentHash(String docId);

    /** All indexed document ids. */
    Set<String> docIds();

    Optional<Chunk> get(String chunkId);

    /**
     * Keyword search over {@link Chunk#contextualText()} (BM25 in the bundled Lucene index).
     *
     * @param filters exact-match metadata filters; null or empty for none
     * @return at most {@code topK} hits, best first, score signal {@link ScoredChunk#BM25}
     */
    List<ScoredChunk> keywordSearch(String query, int topK, Map<String, String> filters);

    /**
     * Keyword search restricted by {@code filter}; null means {@link SearchFilter#none()}.
     *
     * @throws UnsupportedOperationException when the filter restricts document ids and the
     *                                       implementation does not override this method
     */
    default List<ScoredChunk> keywordSearch(String query, int topK, SearchFilter filter) {
        SearchFilter f = filter == null ? SearchFilter.none() : filter;
        if (f.matchesNothing()) {
            return List.of();
        }
        requireNoDocIdRestriction(f);
        return keywordSearch(query, topK, f.metadata());
    }

    /**
     * Approximate nearest-neighbour search by cosine similarity.
     *
     * @param filters exact-match metadata filters; null or empty for none
     * @return at most {@code topK} hits, best first, score signal {@link ScoredChunk#VECTOR}
     */
    List<ScoredChunk> vectorSearch(float[] queryVector, int topK, Map<String, String> filters);

    /**
     * Vector search restricted by {@code filter} (applied as a pre-filter, not after the top
     * {@code topK} are picked); null means {@link SearchFilter#none()}.
     *
     * @throws UnsupportedOperationException when the filter restricts document ids and the
     *                                       implementation does not override this method
     */
    default List<ScoredChunk> vectorSearch(float[] queryVector, int topK, SearchFilter filter) {
        SearchFilter f = filter == null ? SearchFilter.none() : filter;
        if (f.matchesNothing()) {
            return List.of();
        }
        requireNoDocIdRestriction(f);
        return vectorSearch(queryVector, topK, f.metadata());
    }

    /** Number of indexed chunks. */
    long size();

    /**
     * Runs {@code work}, a batch of writes by the calling thread, letting the implementation
     * defer durability work (e.g. commits) until it ends. Every document is still replaced
     * atomically. Default: runs {@code work} directly.
     */
    default void bulk(Runnable work) {
        work.run();
    }

    @Override
    void close();

    private void requireNoDocIdRestriction(SearchFilter filter) {
        if (filter.restrictsDocIds()) {
            throw new UnsupportedOperationException(getClass().getName()
                    + " does not support document-id restrictions; override the SearchFilter overloads");
        }
    }
}
