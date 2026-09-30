package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.internal.KeywordAnalysis;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SearchFilter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.IOUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static io.github.qwzhang01.agent.rag.index.ChunkDocuments.CHUNKS;
import static io.github.qwzhang01.agent.rag.index.ChunkDocuments.F_CHUNK_ID;
import static io.github.qwzhang01.agent.rag.index.ChunkDocuments.F_CONTENT;
import static io.github.qwzhang01.agent.rag.index.ChunkDocuments.F_DOC_ID;
import static io.github.qwzhang01.agent.rag.index.ChunkDocuments.F_HASH;
import static io.github.qwzhang01.agent.rag.index.ChunkDocuments.F_VECTOR;
import static io.github.qwzhang01.agent.rag.index.ChunkDocuments.MARKERS;

/**
 * {@link ChunkIndex} backed by a single Lucene index holding BM25 text and HNSW vectors.
 * <p>
 * Each document is stored as one marker entry (content hash, so documents with zero chunks
 * are still tracked) plus one entry per chunk. {@link #upsert} replaces all entries of a
 * document with one {@code updateDocuments} call, so readers never observe a mix.
 * <p>
 * Keyword search analyzes text with {@link SmartChineseAnalyzer} (Chinese word segmentation,
 * English stemming). Vector search uses cosine similarity; Lucene reports it normalized to
 * {@code (1 + cos) / 2}, i.e. in {@code [0, 1]}, and that value is the {@code vector} score.
 * Zero vectors cannot be scored by cosine and are indexed as keyword-only.
 * <p>
 * Durability: outside {@link #bulk} every write commits (one fsync per document on disk) and is
 * visible to readers when the call returns. Inside {@link #bulk} commits are deferred, see there.
 * Thread-safe: writes are serialized, reads go through a {@link SearcherManager}.
 */
public final class LuceneChunkIndex implements ChunkIndex {

    /** Upper bound on vector dimensions (Lucene's codec default is 1024). */
    public static final int MAX_DIMENSIONS = 4096;

    /**
     * Most document-id prefixes one {@link SearchFilter} may carry here. Each prefix is a query
     * clause and Lucene caps clauses per query ({@link IndexSearcher#getMaxClauseCount()}, 1024 by
     * default), shared with the query terms of keyword search.
     */
    public static final int MAX_DOC_ID_PREFIXES = 512;

    /** Writes after which a {@link #bulk} run commits even though it has not ended. */
    public static final int BULK_COMMIT_INTERVAL = 256;

    private static final String COMMIT_DIMENSIONS = "agent-rag.dimensions";
    private static final int MAX_QUERY_CHARS = 1000;

    private final Directory directory;
    private final int dimensions;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final ThreadLocal<Boolean> inBulk = ThreadLocal.withInitial(() -> false);
    private int uncommittedWrites;
    private volatile boolean closed;

    private LuceneChunkIndex(Directory directory, int dimensions) throws IOException {
        this.directory = directory;
        this.dimensions = dimensions;
        checkStoredDimensions(directory, dimensions);
        Analyzer a = KeywordAnalysis.newAnalyzer();
        IndexWriter w = null;
        try {
            IndexWriterConfig config = new IndexWriterConfig(a)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                    .setCodec(new HighDimensionCodec(Codec.getDefault(), MAX_DIMENSIONS));
            w = new IndexWriter(directory, config);
            w.setLiveCommitData(Map.of(COMMIT_DIMENSIONS, String.valueOf(dimensions)).entrySet());
            w.commit();
            this.searcherManager = new SearcherManager(w, null);
        } catch (IOException | RuntimeException e) {
            IOUtils.closeWhileHandlingException(w, a);
            throw e;
        }
        this.analyzer = a;
        this.writer = w;
    }

    /**
     * Opens (or creates) a persistent index under {@code dir}.
     *
     * @throws IllegalArgumentException when {@code dimensions} is out of range or differs from the
     *                                  dimensions the existing index was created with
     */
    public static LuceneChunkIndex open(Path dir, int dimensions) {
        Objects.requireNonNull(dir, "dir");
        checkDimensions(dimensions);
        Directory directory = null;
        try {
            Files.createDirectories(dir);
            directory = FSDirectory.open(dir);
            return new LuceneChunkIndex(directory, dimensions);
        } catch (IOException e) {
            IOUtils.closeWhileHandlingException(directory);
            throw new UncheckedIOException("Cannot open index at " + dir, e);
        } catch (RuntimeException e) {
            IOUtils.closeWhileHandlingException(directory);
            throw e;
        }
    }

    /** Creates a heap-only index, lost on {@link #close()}. */
    public static LuceneChunkIndex inMemory(int dimensions) {
        checkDimensions(dimensions);
        try {
            return new LuceneChunkIndex(new ByteBuffersDirectory(), dimensions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int dimensions() {
        return dimensions;
    }

    /**
     * Runs {@code work} with commits deferred for writes made by the calling thread: they are
     * committed and made visible to readers together when {@code work} ends (normally or not),
     * and every {@value #BULK_COMMIT_INTERVAL} writes in between. Each document is still replaced
     * atomically, so readers see the pre-bulk or post-bulk version of a document, never a mix.
     * <p>
     * A crash inside a bulk loses the writes since its last commit; the index stays consistent
     * at that commit. Writes from other threads are unaffected and commit immediately (taking
     * pending bulk writes with them). Nested calls join the outer bulk.
     */
    @Override
    public void bulk(Runnable work) {
        Objects.requireNonNull(work, "work");
        ensureOpen();
        if (inBulk.get()) {
            work.run();
            return;
        }
        inBulk.set(true);
        Throwable failure = null;
        try {
            work.run();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            inBulk.remove();
            try {
                commitPending();
            } catch (RuntimeException e) {
                if (failure == null) {
                    throw e;
                }
                failure.addSuppressed(e);
            }
        }
    }

    @Override
    public synchronized void upsert(String docId, String contentHash, List<Chunk> chunks, List<float[]> vectors) {
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(chunks, "chunks");
        if (vectors != null && vectors.size() != chunks.size()) {
            throw new IllegalArgumentException("vectors size " + vectors.size()
                    + " != chunks size " + chunks.size() + " for " + docId);
        }
        ensureOpen();
        List<Document> docs = new ArrayList<>(chunks.size() + 1);
        docs.add(ChunkDocuments.marker(docId, contentHash));
        Set<String> chunkIds = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = Objects.requireNonNull(chunks.get(i), "chunk");
            if (!docId.equals(chunk.docId())) {
                throw new IllegalArgumentException("chunk " + chunk.chunkId() + " belongs to "
                        + chunk.docId() + ", not " + docId);
            }
            if (!chunk.chunkId().startsWith(docId + "#")) {
                throw new IllegalArgumentException("chunkId " + chunk.chunkId()
                        + " must start with " + docId + "#");
            }
            if (!chunkIds.add(chunk.chunkId())) {
                throw new IllegalArgumentException("duplicate chunkId " + chunk.chunkId());
            }
            float[] vector = vectors == null ? null : vectors.get(i);
            if (vector != null && vector.length != dimensions) {
                throw new IllegalArgumentException("vector of " + chunk.chunkId() + " has "
                        + vector.length + " dimensions, index expects " + dimensions);
            }
            docs.add(ChunkDocuments.chunk(chunk, vector));
        }
        try {
            writer.updateDocuments(ChunkDocuments.docTerm(docId), docs);
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot upsert " + docId, e);
        }
    }

    @Override
    public synchronized void delete(String docId) {
        Objects.requireNonNull(docId, "docId");
        ensureOpen();
        try {
            writer.deleteDocuments(ChunkDocuments.docTerm(docId));
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + docId, e);
        }
    }

    @Override
    public Optional<String> contentHash(String docId) {
        Objects.requireNonNull(docId, "docId");
        Query query = new BooleanQuery.Builder()
                .add(MARKERS, Occur.FILTER)
                .add(new TermQuery(ChunkDocuments.docTerm(docId)), Occur.FILTER)
                .build();
        return withSearcher(s -> {
            TopDocs top = s.search(query, 1);
            if (top.scoreDocs.length == 0) {
                return Optional.empty();
            }
            return Optional.ofNullable(s.storedFields().document(top.scoreDocs[0].doc).get(F_HASH));
        });
    }

    @Override
    public Set<String> docIds() {
        return withSearcher(s -> {
            int count = s.count(MARKERS);
            if (count == 0) {
                return Set.of();
            }
            Set<String> ids = new TreeSet<>();
            for (ScoreDoc hit : s.search(MARKERS, count).scoreDocs) {
                ids.add(s.storedFields().document(hit.doc).get(F_DOC_ID));
            }
            return Collections.unmodifiableSet(ids);
        });
    }

    @Override
    public Optional<Chunk> get(String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId");
        Query query = new TermQuery(new Term(F_CHUNK_ID, chunkId));
        return withSearcher(s -> {
            TopDocs top = s.search(query, 1);
            return top.scoreDocs.length == 0
                    ? Optional.empty()
                    : Optional.of(ChunkDocuments.toChunk(s.storedFields().document(top.scoreDocs[0].doc)));
        });
    }

    @Override
    public List<ScoredChunk> keywordSearch(String query, int topK, Map<String, String> filters) {
        return keywordSearch(query, topK, SearchFilter.of(filters));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the filter has more than {@link #MAX_DOC_ID_PREFIXES}
     *                                  prefixes, or so many clauses that no query term fits
     */
    @Override
    public List<ScoredChunk> keywordSearch(String query, int topK, SearchFilter filter) {
        SearchFilter f = filter == null ? SearchFilter.none() : filter;
        if (query == null || query.isBlank() || topK <= 0 || f.matchesNothing()) {
            return List.of();
        }
        // Lucene rejects queries with more than getMaxClauseCount() leaf clauses in total.
        int termBudget = IndexSearcher.getMaxClauseCount() - 2 - filterClauseCount(f);
        if (termBudget < 1) {
            throw new IllegalArgumentException("filter has too many clauses (" + filterClauseCount(f)
                    + "), max clause count is " + IndexSearcher.getMaxClauseCount());
        }
        Query textQuery = textQuery(query, termBudget);
        if (textQuery == null) {
            return List.of();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder()
                .add(textQuery, Occur.MUST)
                .add(CHUNKS, Occur.FILTER);
        addFilters(builder, f);
        return search(builder.build(), topK, ScoredChunk.BM25);
    }

    @Override
    public List<ScoredChunk> vectorSearch(float[] queryVector, int topK, Map<String, String> filters) {
        return vectorSearch(queryVector, topK, SearchFilter.of(filters));
    }

    /**
     * {@inheritDoc}
     * <p>
     * The filter is the KNN pre-filter: HNSW explores only allowed chunks (Lucene falls back to an
     * exact scan when few documents pass), so {@code topK} is filled whenever enough allowed chunks
     * have vectors.
     *
     * @throws IllegalArgumentException when the filter has more than {@link #MAX_DOC_ID_PREFIXES} prefixes
     */
    @Override
    public List<ScoredChunk> vectorSearch(float[] queryVector, int topK, SearchFilter filter) {
        Objects.requireNonNull(queryVector, "queryVector");
        if (queryVector.length != dimensions) {
            throw new IllegalArgumentException("query vector has " + queryVector.length
                    + " dimensions, index expects " + dimensions);
        }
        SearchFilter f = filter == null ? SearchFilter.none() : filter;
        if (topK <= 0 || ChunkDocuments.isZero(queryVector) || f.matchesNothing()) {
            return List.of();
        }
        Query preFilter = null;
        if (!f.isUnrestricted()) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            addFilters(builder, f);
            preFilter = builder.build();
        }
        Query query = new KnnFloatVectorQuery(F_VECTOR, queryVector.clone(), topK, preFilter);
        return search(query, topK, ScoredChunk.VECTOR);
    }

    @Override
    public long size() {
        return withSearcher(s -> (long) s.count(CHUNKS));
    }

    /** Commits pending state (including an unfinished {@link #bulk}) and releases files; idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            IOUtils.close(searcherManager, writer, directory, analyzer);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close index", e);
        }
    }

    /** Analyzed OR query over at most {@code maxTerms} terms; null when nothing survives analysis. */
    private Query textQuery(String query, int maxTerms) {
        String text = query.length() > MAX_QUERY_CHARS ? query.substring(0, MAX_QUERY_CHARS) : query;
        List<String> terms = KeywordAnalysis.tokens(analyzer, text, maxTerms);
        if (terms.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String term : terms) {
            builder.add(new TermQuery(new Term(F_CONTENT, term)), Occur.SHOULD);
        }
        return builder.build();
    }

    private List<ScoredChunk> search(Query query, int topK, String signal) {
        return withSearcher(s -> {
            TopDocs top = s.search(query, topK);
            List<ScoredChunk> hits = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                hits.add(ScoredChunk.of(ChunkDocuments.toChunk(s.storedFields().document(hit.doc)), signal, hit.score));
            }
            return List.copyOf(hits);
        });
    }

    private void afterWrite() throws IOException {
        uncommittedWrites++;
        if (!inBulk.get() || uncommittedWrites >= BULK_COMMIT_INTERVAL) {
            commit();
        }
    }

    private synchronized void commitPending() {
        if (closed || uncommittedWrites == 0) {
            return;
        }
        try {
            commit();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot commit index", e);
        }
    }

    private void commit() throws IOException {
        writer.commit();
        uncommittedWrites = 0;
        searcherManager.maybeRefreshBlocking();
    }

    private <T> T withSearcher(SearcherFunction<T> fn) {
        ensureOpen();
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                return fn.apply(searcher);
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("index is closed");
        }
    }

    /** Leaf clauses the filter adds to a query; validates the prefix count. */
    private static int filterClauseCount(SearchFilter filter) {
        int prefixes = filter.docIdPrefixes().map(Set::size).orElse(0);
        if (prefixes > MAX_DOC_ID_PREFIXES) {
            throw new IllegalArgumentException("at most " + MAX_DOC_ID_PREFIXES
                    + " docId prefixes are supported, got " + prefixes);
        }
        return filter.metadata().size() + prefixes;
    }

    private static void addFilters(BooleanQuery.Builder builder, SearchFilter filter) {
        filterClauseCount(filter);
        filter.metadata().forEach((key, value) ->
                builder.add(ChunkDocuments.metadataFilter(key, value), Occur.FILTER));
        Set<String> prefixes = filter.docIdPrefixes().orElse(Set.of());
        if (prefixes.isEmpty()) {
            return;
        }
        // One nested disjunction instead of a clause per prefix at the top level; every prefix
        // still counts as one leaf towards IndexSearcher.getMaxClauseCount().
        BooleanQuery.Builder anyPrefix = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
        for (String prefix : prefixes) {
            anyPrefix.add(new PrefixQuery(new Term(F_DOC_ID, prefix)), Occur.SHOULD);
        }
        builder.add(anyPrefix.build(), Occur.FILTER);
    }

    private static void checkDimensions(int dimensions) {
        if (dimensions < 1 || dimensions > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("dimensions must be in [1, " + MAX_DIMENSIONS + "]: " + dimensions);
        }
    }

    private static void checkStoredDimensions(Directory directory, int dimensions) throws IOException {
        if (!DirectoryReader.indexExists(directory)) {
            return;
        }
        String stored = SegmentInfos.readLatestCommit(directory).getUserData().get(COMMIT_DIMENSIONS);
        if (stored != null && Integer.parseInt(stored) != dimensions) {
            throw new IllegalArgumentException("index was created with " + stored
                    + " dimensions, requested " + dimensions);
        }
    }

    @FunctionalInterface
    private interface SearcherFunction<T> {
        T apply(IndexSearcher searcher) throws IOException;
    }
}
