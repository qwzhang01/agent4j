package io.github.qwzhang01.agent.rag.index;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Every write commits, which makes writes durable but costs an fsync per document on disk.
 * Thread-safe: writes are serialized, reads go through a {@link SearcherManager}.
 */
public final class LuceneChunkIndex implements ChunkIndex {

    private static final Logger log = LoggerFactory.getLogger(LuceneChunkIndex.class);

    /** Upper bound on vector dimensions (Lucene's codec default is 1024). */
    public static final int MAX_DIMENSIONS = 4096;

    static final String F_KIND = "kind";
    static final String KIND_CHUNK = "chunk";
    static final String KIND_DOC = "doc";
    static final String F_DOC_ID = "docId";
    static final String F_CHUNK_ID = "chunkId";
    static final String F_HASH = "contentHash";
    static final String F_TITLE = "docTitle";
    static final String F_SOURCE = "source";
    static final String F_SECTION = "sectionPath";
    static final String F_TEXT = "text";
    static final String F_START = "startLine";
    static final String F_END = "endLine";
    static final String F_PAGE = "page";
    static final String F_META = "metadata";
    static final String F_CONTENT = "content";
    static final String F_VECTOR = "vector";
    static final String META_PREFIX = "meta.";

    private static final String COMMIT_DIMENSIONS = "agent-rag.dimensions";
    // Keeps analyzed SHOULD clauses below BooleanQuery's default 1024-clause limit.
    private static final int MAX_QUERY_CHARS = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final Query CHUNKS = new TermQuery(new Term(F_KIND, KIND_CHUNK));
    private static final Query MARKERS = new TermQuery(new Term(F_KIND, KIND_DOC));

    private final Directory directory;
    private final int dimensions;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private volatile boolean closed;

    private LuceneChunkIndex(Directory directory, int dimensions) throws IOException {
        this.directory = directory;
        this.dimensions = dimensions;
        this.analyzer = new SmartChineseAnalyzer();
        checkStoredDimensions(directory, dimensions);
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setCodec(new HighDimensionCodec(Codec.getDefault()));
        IndexWriter w = new IndexWriter(directory, config);
        try {
            w.setLiveCommitData(Map.of(COMMIT_DIMENSIONS, String.valueOf(dimensions)).entrySet());
            w.commit();
            this.searcherManager = new SearcherManager(w, null);
        } catch (IOException | RuntimeException e) {
            IOUtils.closeWhileHandlingException(w);
            throw e;
        }
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
        docs.add(markerDocument(docId, contentHash));
        Set<String> chunkIds = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = Objects.requireNonNull(chunks.get(i), "chunk");
            if (!docId.equals(chunk.docId())) {
                throw new IllegalArgumentException("chunk " + chunk.chunkId() + " belongs to "
                        + chunk.docId() + ", not " + docId);
            }
            if (!chunkIds.add(chunk.chunkId())) {
                throw new IllegalArgumentException("duplicate chunkId " + chunk.chunkId());
            }
            float[] vector = vectors == null ? null : vectors.get(i);
            if (vector != null && vector.length != dimensions) {
                throw new IllegalArgumentException("vector of " + chunk.chunkId() + " has "
                        + vector.length + " dimensions, index expects " + dimensions);
            }
            docs.add(chunkDocument(chunk, vector));
        }
        try {
            writer.updateDocuments(new Term(F_DOC_ID, docId), docs);
            commit();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot upsert " + docId, e);
        }
    }

    @Override
    public synchronized void delete(String docId) {
        Objects.requireNonNull(docId, "docId");
        ensureOpen();
        try {
            writer.deleteDocuments(new Term(F_DOC_ID, docId));
            commit();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + docId, e);
        }
    }

    @Override
    public Optional<String> contentHash(String docId) {
        Objects.requireNonNull(docId, "docId");
        Query query = new BooleanQuery.Builder()
                .add(MARKERS, Occur.FILTER)
                .add(new TermQuery(new Term(F_DOC_ID, docId)), Occur.FILTER)
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
                    : Optional.of(toChunk(s, top.scoreDocs[0].doc));
        });
    }

    @Override
    public List<ScoredChunk> keywordSearch(String query, int topK, Map<String, String> filters) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        String text = query.length() > MAX_QUERY_CHARS ? query.substring(0, MAX_QUERY_CHARS) : query;
        Query textQuery = new QueryBuilder(analyzer).createBooleanQuery(F_CONTENT, text, Occur.SHOULD);
        if (textQuery == null) {
            return List.of();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder()
                .add(textQuery, Occur.MUST)
                .add(CHUNKS, Occur.FILTER);
        addFilters(builder, filters);
        return search(builder.build(), topK, ScoredChunk.BM25);
    }

    @Override
    public List<ScoredChunk> vectorSearch(float[] queryVector, int topK, Map<String, String> filters) {
        Objects.requireNonNull(queryVector, "queryVector");
        if (queryVector.length != dimensions) {
            throw new IllegalArgumentException("query vector has " + queryVector.length
                    + " dimensions, index expects " + dimensions);
        }
        if (topK <= 0 || isZero(queryVector)) {
            return List.of();
        }
        Query filter = null;
        if (filters != null && !filters.isEmpty()) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            addFilters(builder, filters);
            filter = builder.build();
        }
        Query query = new KnnFloatVectorQuery(F_VECTOR, queryVector.clone(), topK, filter);
        return search(query, topK, ScoredChunk.VECTOR);
    }

    @Override
    public long size() {
        return withSearcher(s -> (long) s.count(CHUNKS));
    }

    /** Commits pending state and releases files; idempotent. */
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

    private List<ScoredChunk> search(Query query, int topK, String signal) {
        return withSearcher(s -> {
            TopDocs top = s.search(query, topK);
            List<ScoredChunk> hits = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                hits.add(ScoredChunk.of(toChunk(s, hit.doc), signal, hit.score));
            }
            return List.copyOf(hits);
        });
    }

    private void commit() throws IOException {
        writer.commit();
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

    private static void addFilters(BooleanQuery.Builder builder, Map<String, String> filters) {
        if (filters == null) {
            return;
        }
        filters.forEach((key, value) -> builder.add(
                new TermQuery(new Term(META_PREFIX + Objects.requireNonNull(key, "filter key"),
                        Objects.requireNonNull(value, "filter value"))),
                Occur.FILTER));
    }

    private static Document markerDocument(String docId, String contentHash) {
        Document doc = new Document();
        doc.add(new StringField(F_KIND, KIND_DOC, Field.Store.NO));
        doc.add(new StringField(F_DOC_ID, docId, Field.Store.YES));
        doc.add(new StoredField(F_HASH, contentHash));
        return doc;
    }

    private static Document chunkDocument(Chunk chunk, float[] vector) {
        Document doc = new Document();
        doc.add(new StringField(F_KIND, KIND_CHUNK, Field.Store.NO));
        doc.add(new StringField(F_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
        doc.add(new StringField(F_DOC_ID, chunk.docId(), Field.Store.YES));
        doc.add(new StoredField(F_TITLE, chunk.docTitle()));
        if (chunk.source() != null) {
            doc.add(new StoredField(F_SOURCE, chunk.source()));
        }
        doc.add(new StoredField(F_SECTION, writeJson(chunk.sectionPath())));
        doc.add(new StoredField(F_TEXT, chunk.text()));
        doc.add(new StoredField(F_START, chunk.startLine()));
        doc.add(new StoredField(F_END, chunk.endLine()));
        if (chunk.page() != null) {
            doc.add(new StoredField(F_PAGE, chunk.page()));
        }
        doc.add(new StoredField(F_META, writeJson(chunk.metadata())));
        doc.add(new TextField(F_CONTENT, chunk.contextualText(), Field.Store.NO));
        chunk.metadata().forEach((key, value) ->
                doc.add(new StringField(META_PREFIX + key, value, Field.Store.NO)));
        if (vector != null) {
            if (isZero(vector)) {
                log.debug("Zero vector for chunk {}; indexing keyword-only", chunk.chunkId());
            } else {
                doc.add(new KnnFloatVectorField(F_VECTOR, vector.clone(), VectorSimilarityFunction.COSINE));
            }
        }
        return doc;
    }

    private static Chunk toChunk(IndexSearcher searcher, int docNumber) throws IOException {
        Document doc = searcher.storedFields().document(docNumber);
        IndexableField page = doc.getField(F_PAGE);
        return new Chunk(
                doc.get(F_CHUNK_ID),
                doc.get(F_DOC_ID),
                doc.get(F_TITLE),
                doc.get(F_SOURCE),
                readJson(doc.get(F_SECTION), STRING_LIST),
                doc.get(F_TEXT),
                doc.getField(F_START).numericValue().intValue(),
                doc.getField(F_END).numericValue().intValue(),
                page == null ? null : page.numericValue().intValue(),
                readJson(doc.get(F_META), STRING_MAP));
    }

    private static boolean isZero(float[] vector) {
        for (float v : vector) {
            if (v != 0f) {
                return false;
            }
        }
        return true;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + value, e);
        }
    }

    private static <T> T readJson(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored field: " + json, e);
        }
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

    /**
     * Default codec with a higher vector dimension limit. Keeps the delegate's name, so the
     * index stays readable by a stock Lucene codec; the limit only applies at write time.
     */
    private static final class HighDimensionCodec extends FilterCodec {

        private final KnnVectorsFormat vectors;

        HighDimensionCodec(Codec delegate) {
            super(delegate.getName(), delegate);
            this.vectors = new MaxDimensionsFormat(delegate.knnVectorsFormat());
        }

        @Override
        public KnnVectorsFormat knnVectorsFormat() {
            return vectors;
        }
    }

    private static final class MaxDimensionsFormat extends KnnVectorsFormat {

        private final KnnVectorsFormat delegate;

        MaxDimensionsFormat(KnnVectorsFormat delegate) {
            super(delegate.getName());
            this.delegate = delegate;
        }

        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
            return delegate.fieldsWriter(state);
        }

        @Override
        public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
            return delegate.fieldsReader(state);
        }

        @Override
        public int getMaxDimensions(String fieldName) {
            return MAX_DIMENSIONS;
        }
    }
}
