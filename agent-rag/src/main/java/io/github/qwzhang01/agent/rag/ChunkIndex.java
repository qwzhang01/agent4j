package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;

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
     * BM25 search over {@link Chunk#contextualText()}.
     *
     * @param filters exact-match metadata filters; empty for none
     * @return at most {@code topK} hits, best first, score signal {@link ScoredChunk#BM25}
     */
    List<ScoredChunk> keywordSearch(String query, int topK, Map<String, String> filters);

    /**
     * Approximate nearest-neighbour search by cosine similarity.
     *
     * @return at most {@code topK} hits, best first, score signal {@link ScoredChunk#VECTOR}
     */
    List<ScoredChunk> vectorSearch(float[] queryVector, int topK, Map<String, String> filters);

    /** Number of indexed chunks. */
    long size();

    @Override
    void close();
}
