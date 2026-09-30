package io.github.qwzhang01.agent.rag.pgvector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.internal.KeywordAnalysis;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SearchFilter;
import org.apache.lucene.analysis.Analyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@link ChunkIndex} on PostgreSQL with the pgvector extension, so several application instances
 * share one index.
 * <p>
 * <b>Schema.</b> Two tables named after {@link Builder#tablePrefix} (default {@code rag_}):
 * {@code <prefix>docs} (one row per document: id, content hash, update time) and
 * {@code <prefix>chunks} (every {@link Chunk} field, the embedding as {@code vector(N)}, null for
 * keyword-only chunks, and keyword tokens as {@code tsvector}). Indexes: HNSW on the embedding with
 * {@code vector_cosine_ops} (only up to {@value #MAX_HNSW_DIMENSIONS} dimensions, pgvector's limit
 * for {@code vector}; above that searches are exact scans), GIN on the tokens, GIN
 * ({@code jsonb_path_ops}) on the metadata, and a {@code text_pattern_ops} btree on {@code doc_id}.
 * {@link Builder#createSchema} runs the idempotent DDL at build time;
 * {@link Builder#schemaStatements()} returns it for a DBA to run instead. Opening a table created
 * with another vector dimension fails, as with the Lucene index.
 * <p>
 * <b>Writes.</b> {@link #upsert} validates like {@code LuceneChunkIndex}, then in one transaction
 * takes {@code pg_advisory_xact_lock} on (chunks table, docId), upserts the document row, deletes
 * the old chunks and batch-inserts the new ones. Under READ COMMITTED every search is one statement,
 * so it sees the old or the new version of a document, never a mix; the advisory lock serializes
 * writers of the same document across instances. PostgreSQL text cannot contain U+0000, so chunks,
 * ids and metadata containing it are rejected with {@link IllegalArgumentException}.
 * <p>
 * <b>Keyword search</b> is not BM25. PostgreSQL has no Chinese parser, so text is tokenized in Java
 * with the analyzer agent-rag's Lucene index uses ({@link KeywordAnalysis}) and stored as a
 * {@code tsvector} of those exact lexemes with positions (no PostgreSQL parsing or stemming). A query
 * is tokenized the same way and matched as an OR of its terms, ranked by
 * {@code ts_rank_cd(tokens, query, 1|32)}: cover density, divided by {@code 1 + log(length)} so long
 * chunks do not win by size alone (BM25's length normalization plays the same role), then mapped by
 * {@code rank / (rank + 1)} into {@code [0, 1)}. Rankings are close to BM25 on short queries but
 * scores are not comparable with Lucene's; fusion (RRF) only uses ranks. Chunks keep at most
 * {@value PgText#MAX_POSITION} token positions.
 * <p>
 * <b>Vector search</b> orders by cosine distance {@code <=>} and reports
 * {@code 1 - distance / 2 = (1 + cos) / 2}, the same {@code [0, 1]} scale as the Lucene index.
 * Filters go into the {@code WHERE} clause. With a filter (or {@code topK} above
 * {@code ef_search}) the query runs with {@code SET LOCAL hnsw.iterative_scan = relaxed_order}
 * (pgvector 0.8+), so the HNSW scan continues until {@code topK} allowed rows are found or
 * {@code hnsw.max_scan_tuples} (default 20000) is reached; results are re-sorted by distance.
 * {@code hnsw.ef_search} is set per query to {@code max(efSearch, topK)}, capped at 1000.
 * <p>
 * <b>Filters.</b> Metadata filters are {@code metadata @> ?::jsonb}; document-id prefixes are
 * {@code doc_id LIKE ANY (?)} with {@code %}, {@code _} and {@code \} escaped, one array
 * parameter however many prefixes there are.
 * <p>
 * <b>Lifecycle.</b> The index never closes the {@link DataSource}: the caller created it and owns
 * it. {@link #close()} only releases the analyzer and blocks further use. Thread-safe.
 */
public final class PgChunkIndex implements ChunkIndex, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgChunkIndex.class);

    /** Largest dimension of pgvector's {@code vector} type. */
    public static final int MAX_DIMENSIONS = 16000;
    /** Largest dimension pgvector can put in an HNSW index for {@code vector}. */
    public static final int MAX_HNSW_DIMENSIONS = 2000;
    public static final String DEFAULT_TABLE_PREFIX = "rag_";
    public static final int DEFAULT_HNSW_M = 16;
    public static final int DEFAULT_HNSW_EF_CONSTRUCTION = 64;
    /** pgvector's default {@code hnsw.ef_search}. */
    public static final int DEFAULT_EF_SEARCH = 40;
    static final int MAX_EF_SEARCH = 1000;
    static final int MAX_QUERY_CHARS = 1000;
    static final int MAX_QUERY_TERMS = 1024;
    static final int RANK_NORMALIZATION = 1 | 32;

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final int MAX_PREFIX_LENGTH = 40;
    private static final String LOCK_NAMESPACE = "agent-rag-pgvector";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final String COLUMNS = "c.chunk_id, c.doc_id, c.doc_title, c.source, c.section_path, "
            + "c.body, c.start_line, c.end_line, c.page, c.metadata";

    private final DataSource dataSource;
    private final int dimensions;
    private final String docsTable;
    private final String chunksTable;
    private final int efSearch;
    private final boolean iterativeScan;
    private final Analyzer analyzer;
    private volatile boolean closed;

    private PgChunkIndex(Builder b) {
        this.dataSource = b.dataSource;
        this.dimensions = b.dimensions;
        this.docsTable = b.qualified("docs");
        this.chunksTable = b.qualified("chunks");
        this.efSearch = b.efSearch;
        this.iterativeScan = b.iterativeScan;
        if (b.createSchema) {
            createSchema(b);
        }
        checkSchema();
        this.analyzer = KeywordAnalysis.newAnalyzer();
    }

    /** Builder over a caller-owned {@link DataSource}; the index never closes it. */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    public int dimensions() {
        return dimensions;
    }

    /** Schema-qualified (when a schema was set) name of the chunks table. */
    public String chunksTable() {
        return chunksTable;
    }

    /** Schema-qualified (when a schema was set) name of the documents table. */
    public String docsTable() {
        return docsTable;
    }

    @Override
    public void upsert(String docId, String contentHash, List<Chunk> chunks, List<float[]> vectors) {
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(chunks, "chunks");
        if (vectors != null && vectors.size() != chunks.size()) {
            throw new IllegalArgumentException("vectors size " + vectors.size()
                    + " != chunks size " + chunks.size() + " for " + docId);
        }
        requireNoNul(docId, "docId", docId);
        requireNoNul(contentHash, "contentHash", docId);
        ensureOpen();
        List<Row> rows = new ArrayList<>(chunks.size());
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
            requireNoNul(chunk);
            float[] vector = vectors == null ? null : vectors.get(i);
            if (vector != null) {
                if (vector.length != dimensions) {
                    throw new IllegalArgumentException("vector of " + chunk.chunkId() + " has "
                            + vector.length + " dimensions, index expects " + dimensions);
                }
                requireFinite(vector, chunk.chunkId());
                if (isZero(vector)) {
                    log.debug("Zero vector for chunk {}; indexing keyword-only", chunk.chunkId());
                    vector = null;
                }
            }
            List<String> tokens = KeywordAnalysis.tokens(analyzer, chunk.contextualText(), PgText.MAX_POSITION);
            rows.add(new Row(chunk, i, vector, PgText.tsvector(tokens)));
        }
        inTransaction("Cannot upsert " + docId, c -> {
            lockDocument(c, docId);
            try (PreparedStatement ps = c.prepareStatement("insert into " + docsTable
                    + " (doc_id, content_hash, updated_at) values (?, ?, now())"
                    + " on conflict (doc_id) do update set content_hash = excluded.content_hash, updated_at = now()")) {
                ps.setString(1, docId);
                ps.setString(2, contentHash);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("delete from " + chunksTable + " where doc_id = ?")) {
                ps.setString(1, docId);
                ps.executeUpdate();
            }
            if (!rows.isEmpty()) {
                insertChunks(c, rows);
            }
            return null;
        });
    }

    @Override
    public void delete(String docId) {
        Objects.requireNonNull(docId, "docId");
        ensureOpen();
        if (PgText.hasNul(docId)) {
            return;
        }
        inTransaction("Cannot delete " + docId, c -> {
            lockDocument(c, docId);
            try (PreparedStatement ps = c.prepareStatement("delete from " + docsTable + " where doc_id = ?")) {
                ps.setString(1, docId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<String> contentHash(String docId) {
        Objects.requireNonNull(docId, "docId");
        ensureOpen();
        if (PgText.hasNul(docId)) {
            return Optional.empty();
        }
        return withConnection("Cannot read content hash of " + docId, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "select content_hash from " + docsTable + " where doc_id = ?")) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    @Override
    public Set<String> docIds() {
        ensureOpen();
        return withConnection("Cannot list documents", c -> {
            Set<String> ids = new TreeSet<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select doc_id from " + docsTable)) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return Collections.unmodifiableSet(ids);
        });
    }

    @Override
    public Optional<Chunk> get(String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId");
        ensureOpen();
        if (PgText.hasNul(chunkId)) {
            return Optional.empty();
        }
        return withConnection("Cannot read chunk " + chunkId, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "select " + COLUMNS + " from " + chunksTable + " c where c.chunk_id = ?")) {
                ps.setString(1, chunkId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(toChunk(rs)) : Optional.<Chunk>empty();
                }
            }
        });
    }

    @Override
    public List<ScoredChunk> keywordSearch(String query, int topK, Map<String, String> filters) {
        return keywordSearch(query, topK, SearchFilter.of(filters));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Ranked by {@code ts_rank_cd}, not BM25; see the class documentation.
     */
    @Override
    public List<ScoredChunk> keywordSearch(String query, int topK, SearchFilter filter) {
        SearchFilter f = filter == null ? SearchFilter.none() : filter;
        if (query == null || query.isBlank() || topK <= 0 || f.matchesNothing()) {
            return List.of();
        }
        ensureOpen();
        String text = query.length() > MAX_QUERY_CHARS ? query.substring(0, MAX_QUERY_CHARS) : query;
        String tsquery = PgText.orQuery(KeywordAnalysis.tokens(analyzer, text, MAX_QUERY_TERMS * 4), MAX_QUERY_TERMS);
        Where where = Where.of(f);
        if (tsquery == null || where == null) {
            return List.of();
        }
        String sql = "select " + COLUMNS + ", ts_rank_cd(c.tokens, q.query, " + RANK_NORMALIZATION + ") as score"
                + " from " + chunksTable + " c, (select ?::tsquery as query) q"
                + " where c.tokens @@ q.query" + where.sql
                + " order by score desc, c.chunk_id limit ?";
        return withConnection("Keyword search failed", c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, tsquery);
                i = where.bind(c, ps, i);
                ps.setInt(i, topK);
                return collect(ps, ScoredChunk.BM25, rs -> rs.getDouble("score"));
            }
        });
    }

    @Override
    public List<ScoredChunk> vectorSearch(float[] queryVector, int topK, Map<String, String> filters) {
        return vectorSearch(queryVector, topK, SearchFilter.of(filters));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Filters are part of the SQL {@code WHERE}; with iterative index scans enabled the HNSW scan
     * keeps going past filtered-out rows. See the class documentation.
     */
    @Override
    public List<ScoredChunk> vectorSearch(float[] queryVector, int topK, SearchFilter filter) {
        Objects.requireNonNull(queryVector, "queryVector");
        if (queryVector.length != dimensions) {
            throw new IllegalArgumentException("query vector has " + queryVector.length
                    + " dimensions, index expects " + dimensions);
        }
        requireFinite(queryVector, "query vector");
        SearchFilter f = filter == null ? SearchFilter.none() : filter;
        if (topK <= 0 || isZero(queryVector) || f.matchesNothing()) {
            return List.of();
        }
        ensureOpen();
        Where where = Where.of(f);
        if (where == null) {
            return List.of();
        }
        int ef = Math.min(MAX_EF_SEARCH, Math.max(efSearch, topK));
        boolean iterative = iterativeScan && (!where.sql.isEmpty() || topK > ef);
        String sql = "with hits as materialized (select " + COLUMNS + ", c.embedding <=> ? as distance"
                + " from " + chunksTable + " c where c.embedding is not null" + where.sql
                + " order by c.embedding <=> ? limit ?)"
                + " select * from hits order by distance, chunk_id";
        PGvector vector = new PGvector(queryVector);
        return inTransaction("Vector search failed", c -> {
            setLocal(c, "hnsw.ef_search", String.valueOf(ef));
            if (iterative) {
                setLocal(c, "hnsw.iterative_scan", "relaxed_order");
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setObject(i++, vector);
                i = where.bind(c, ps, i);
                ps.setObject(i++, vector);
                ps.setInt(i, topK);
                return collect(ps, ScoredChunk.VECTOR, rs -> 1.0 - rs.getDouble("distance") / 2.0);
            }
        });
    }

    @Override
    public long size() {
        ensureOpen();
        return withConnection("Cannot count chunks", c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select count(*) from " + chunksTable)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    /** Blocks further use and releases the analyzer; idempotent. Never closes the {@link DataSource}. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        analyzer.close();
    }

    @Override
    public String toString() {
        return "PgChunkIndex[" + chunksTable + ", dimensions=" + dimensions + "]";
    }

    private void insertChunks(Connection c, List<Row> rows) throws SQLException {
        String sql = "insert into " + chunksTable + " (chunk_id, doc_id, ordinal, doc_title, source, section_path,"
                + " body, start_line, end_line, page, metadata, embedding, tokens)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::tsvector)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Row row : rows) {
                Chunk chunk = row.chunk;
                ps.setString(1, chunk.chunkId());
                ps.setString(2, chunk.docId());
                ps.setInt(3, row.ordinal);
                ps.setString(4, chunk.docTitle());
                ps.setString(5, chunk.source());
                ps.setArray(6, c.createArrayOf("text", chunk.sectionPath().toArray()));
                ps.setString(7, chunk.text());
                ps.setInt(8, chunk.startLine());
                ps.setInt(9, chunk.endLine());
                if (chunk.page() == null) {
                    ps.setNull(10, Types.INTEGER);
                } else {
                    ps.setInt(10, chunk.page());
                }
                ps.setString(11, writeJson(chunk.metadata()));
                if (row.vector == null) {
                    ps.setNull(12, Types.OTHER);
                } else {
                    ps.setObject(12, new PGvector(row.vector));
                }
                ps.setString(13, row.tsvector);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void lockDocument(Connection c, String docId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select pg_advisory_xact_lock(hashtext(?), hashtext(?))")) {
            ps.setString(1, chunksTable);
            ps.setString(2, docId);
            ps.execute();
        }
    }

    private static void setLocal(Connection c, String name, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config(?, ?, true)")) {
            ps.setString(1, name);
            ps.setString(2, value);
            ps.execute();
        }
    }

    private static List<ScoredChunk> collect(PreparedStatement ps, String signal, SqlFunction<ResultSet, Double> score)
            throws SQLException {
        List<ScoredChunk> hits = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                hits.add(ScoredChunk.of(toChunk(rs), signal, score.apply(rs)));
            }
        }
        return List.copyOf(hits);
    }

    private static Chunk toChunk(ResultSet rs) throws SQLException {
        Array section = rs.getArray("section_path");
        List<String> sectionPath;
        try {
            sectionPath = List.of((String[]) section.getArray());
        } finally {
            section.free();
        }
        return new Chunk(
                rs.getString("chunk_id"),
                rs.getString("doc_id"),
                rs.getString("doc_title"),
                rs.getString("source"),
                sectionPath,
                rs.getString("body"),
                rs.getInt("start_line"),
                rs.getInt("end_line"),
                (Integer) rs.getObject("page"),
                readJson(rs.getString("metadata")));
    }

    private void createSchema(Builder b) {
        List<String> ddl = b.schemaStatements();
        inTransaction("Cannot create schema for " + chunksTable, c -> {
            // Concurrent CREATE ... IF NOT EXISTS from several instances can still collide in the
            // catalogs; serialize them.
            try (PreparedStatement ps = c.prepareStatement("select pg_advisory_xact_lock(hashtext(?), hashtext(?))")) {
                ps.setString(1, LOCK_NAMESPACE);
                ps.setString(2, chunksTable);
                ps.execute();
            }
            try (Statement st = c.createStatement()) {
                for (String sql : ddl) {
                    st.execute(sql);
                }
            }
            return null;
        });
        if (dimensions > MAX_HNSW_DIMENSIONS) {
            log.warn("{} dimensions exceed pgvector's HNSW limit of {}; {} has no vector index and "
                    + "vector search scans every row", dimensions, MAX_HNSW_DIMENSIONS, chunksTable);
        }
    }

    private void checkSchema() {
        withConnection("Cannot inspect " + chunksTable, c -> {
            if (!tableExists(c, docsTable) || !tableExists(c, chunksTable)) {
                throw new IllegalStateException("Tables " + docsTable + " / " + chunksTable
                        + " do not exist; enable createSchema or run Builder.schemaStatements()");
            }
            try (PreparedStatement ps = c.prepareStatement("select a.atttypmod, format_type(a.atttypid, a.atttypmod)"
                    + " from pg_attribute a where a.attrelid = to_regclass(?) and a.attname = 'embedding'"
                    + " and not a.attisdropped")) {
                ps.setString(1, chunksTable);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(chunksTable + " has no embedding column");
                    }
                    int stored = rs.getInt(1);
                    if (stored != dimensions) {
                        throw new IllegalArgumentException("table " + chunksTable + " was created with "
                                + rs.getString(2) + " (" + stored + " dimensions), requested " + dimensions);
                    }
                }
            }
            return null;
        });
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select to_regclass(?) is not null")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private <T> T withConnection(String what, SqlFunction<Connection, T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.apply(c);
        } catch (SQLException e) {
            throw new PgChunkIndexException(what + ": " + e.getMessage(), e);
        }
    }

    private <T> T inTransaction(String what, SqlFunction<Connection, T> work) {
        return withConnection(what, c -> {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        });
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("index is closed");
        }
    }

    private static void requireNoNul(Chunk chunk) {
        String id = chunk.chunkId();
        requireNoNul(id, "chunkId", id);
        requireNoNul(chunk.docTitle(), "docTitle", id);
        requireNoNul(chunk.source(), "source", id);
        requireNoNul(chunk.text(), "text", id);
        chunk.sectionPath().forEach(s -> requireNoNul(s, "sectionPath", id));
        chunk.metadata().forEach((k, v) -> {
            requireNoNul(k, "metadata key", id);
            requireNoNul(v, "metadata value", id);
        });
    }

    private static void requireNoNul(String value, String field, String owner) {
        if (PgText.hasNul(value)) {
            throw new IllegalArgumentException(field + " of " + owner
                    + " contains U+0000, which PostgreSQL text cannot store");
        }
    }

    private static void requireFinite(float[] vector, String owner) {
        for (float v : vector) {
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException(owner + " contains a non-finite value");
            }
        }
    }

    private static boolean isZero(float[] vector) {
        for (float v : vector) {
            if (v != 0f) {
                return false;
            }
        }
        return true;
    }

    private static String writeJson(Map<String, String> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + value, e);
        }
    }

    private static Map<String, String> readJson(String json) {
        try {
            return JSON.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt metadata column: " + json, e);
        }
    }

    @FunctionalInterface
    private interface SqlFunction<A, R> {
        R apply(A arg) throws SQLException;
    }

    private record Row(Chunk chunk, int ordinal, float[] vector, String tsvector) {
    }

    /** SQL fragment ({@code " and ..."} or empty) for a filter, and its binder. */
    private static final class Where {
        final String sql;
        private final String metadataJson;
        private final String[] likePatterns;

        private Where(String sql, String metadataJson, String[] likePatterns) {
            this.sql = sql;
            this.metadataJson = metadataJson;
            this.likePatterns = likePatterns;
        }

        /** Null when the filter can match nothing stored (empty prefix set, or values with U+0000). */
        static Where of(SearchFilter filter) {
            StringBuilder sql = new StringBuilder();
            String json = null;
            if (!filter.metadata().isEmpty()) {
                for (Map.Entry<String, String> e : filter.metadata().entrySet()) {
                    if (PgText.hasNul(e.getKey()) || PgText.hasNul(e.getValue())) {
                        return null;
                    }
                }
                json = writeJson(filter.metadata());
                sql.append(" and c.metadata @> ?::jsonb");
            }
            String[] patterns = null;
            Optional<Set<String>> prefixes = filter.docIdPrefixes();
            if (prefixes.isPresent() && !prefixes.get().contains("")) {
                List<String> list = new ArrayList<>(prefixes.get().size());
                for (String prefix : prefixes.get()) {
                    if (!PgText.hasNul(prefix)) {
                        list.add(PgText.likePrefix(prefix));
                    }
                }
                if (list.isEmpty()) {
                    return null;
                }
                patterns = list.toArray(String[]::new);
                sql.append(" and c.doc_id like any (?)");
            }
            return new Where(sql.toString(), json, patterns);
        }

        int bind(Connection c, PreparedStatement ps, int index) throws SQLException {
            int i = index;
            if (metadataJson != null) {
                ps.setString(i++, metadataJson);
            }
            if (likePatterns != null) {
                ps.setArray(i++, c.createArrayOf("text", likePatterns));
            }
            return i;
        }
    }

    /** Builder; {@link #dimensions} is required. */
    public static final class Builder {
        private final DataSource dataSource;
        private int dimensions;
        private String schema;
        private String tablePrefix = DEFAULT_TABLE_PREFIX;
        private int hnswM = DEFAULT_HNSW_M;
        private int hnswEfConstruction = DEFAULT_HNSW_EF_CONSTRUCTION;
        private int efSearch = DEFAULT_EF_SEARCH;
        private boolean iterativeScan = true;
        private boolean createSchema = true;
        private boolean createExtension = true;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        }

        /** Embedding dimensions, in {@code [1, }{@value #MAX_DIMENSIONS}{@code ]}; required. */
        public Builder dimensions(int dimensions) {
            if (dimensions < 1 || dimensions > MAX_DIMENSIONS) {
                throw new IllegalArgumentException("dimensions must be in [1, " + MAX_DIMENSIONS + "]: " + dimensions);
            }
            this.dimensions = dimensions;
            return this;
        }

        /** Schema holding the tables; default null, i.e. the connection's {@code search_path}. */
        public Builder schema(String schema) {
            this.schema = schema == null ? null : identifier(schema, "schema", 63);
            return this;
        }

        /**
         * Table and index name prefix, default {@value #DEFAULT_TABLE_PREFIX}; lower-case letters,
         * digits and {@code _}, starting with a letter or {@code _}, at most 40 characters.
         */
        public Builder tablePrefix(String tablePrefix) {
            this.tablePrefix = identifier(Objects.requireNonNull(tablePrefix, "tablePrefix"), "tablePrefix",
                    MAX_PREFIX_LENGTH);
            return this;
        }

        /** HNSW {@code m} (graph degree), in {@code [2, 100]}; default {@value #DEFAULT_HNSW_M}. */
        public Builder hnswM(int m) {
            if (m < 2 || m > 100) {
                throw new IllegalArgumentException("hnsw m must be in [2, 100]: " + m);
            }
            this.hnswM = m;
            return this;
        }

        /**
         * HNSW {@code ef_construction}, in {@code [4, 1000]} and at least {@code 2 * m};
         * default {@value #DEFAULT_HNSW_EF_CONSTRUCTION}. Only affects a newly created index.
         */
        public Builder hnswEfConstruction(int efConstruction) {
            if (efConstruction < 4 || efConstruction > 1000) {
                throw new IllegalArgumentException("hnsw ef_construction must be in [4, 1000]: " + efConstruction);
            }
            this.hnswEfConstruction = efConstruction;
            return this;
        }

        /**
         * Minimum {@code hnsw.ef_search} per query, in {@code [1, 1000]}; default
         * {@value #DEFAULT_EF_SEARCH}. Each query uses {@code max(efSearch, topK)}, capped at 1000.
         */
        public Builder efSearch(int efSearch) {
            if (efSearch < 1 || efSearch > MAX_EF_SEARCH) {
                throw new IllegalArgumentException("efSearch must be in [1, " + MAX_EF_SEARCH + "]: " + efSearch);
            }
            this.efSearch = efSearch;
            return this;
        }

        /**
         * Whether filtered vector searches set {@code hnsw.iterative_scan = relaxed_order}; default
         * true. Requires pgvector 0.8+. Without it a restrictive filter can return fewer than
         * {@code topK} rows even though more allowed rows exist.
         */
        public Builder iterativeScan(boolean iterativeScan) {
            this.iterativeScan = iterativeScan;
            return this;
        }

        /** Run the idempotent DDL at build time; default true. */
        public Builder createSchema(boolean createSchema) {
            this.createSchema = createSchema;
            return this;
        }

        /**
         * Include {@code CREATE EXTENSION IF NOT EXISTS vector} in the DDL; default true. Turn off
         * when the application role may not create extensions and a DBA installed pgvector.
         */
        public Builder createExtension(boolean createExtension) {
            this.createExtension = createExtension;
            return this;
        }

        /** The DDL {@link #createSchema} runs, in order; for running it out of band. */
        public List<String> schemaStatements() {
            requireDimensions();
            if (hnswEfConstruction < 2 * hnswM) {
                throw new IllegalArgumentException("hnsw ef_construction (" + hnswEfConstruction
                        + ") must be >= 2 * m (" + hnswM + ")");
            }
            String docs = qualified("docs");
            String chunks = qualified("chunks");
            List<String> ddl = new ArrayList<>();
            if (createExtension) {
                ddl.add("create extension if not exists vector");
            }
            ddl.add("create table if not exists " + docs + " ("
                    + "doc_id text primary key, "
                    + "content_hash text not null, "
                    + "updated_at timestamptz not null default now())");
            ddl.add("create table if not exists " + chunks + " ("
                    + "chunk_id text primary key, "
                    + "doc_id text not null references " + docs + " (doc_id) on delete cascade, "
                    + "ordinal integer not null, "
                    + "doc_title text not null, "
                    + "source text, "
                    + "section_path text[] not null, "
                    + "body text not null, "
                    + "start_line integer not null, "
                    + "end_line integer not null, "
                    + "page integer, "
                    + "metadata jsonb not null, "
                    + "embedding vector(" + dimensions + "), "
                    + "tokens tsvector not null)");
            ddl.add("create index if not exists " + tablePrefix + "chunks_doc_id on " + chunks
                    + " (doc_id text_pattern_ops)");
            ddl.add("create index if not exists " + tablePrefix + "chunks_tokens_gin on " + chunks
                    + " using gin (tokens)");
            ddl.add("create index if not exists " + tablePrefix + "chunks_metadata_gin on " + chunks
                    + " using gin (metadata jsonb_path_ops)");
            if (dimensions <= MAX_HNSW_DIMENSIONS) {
                ddl.add("create index if not exists " + tablePrefix + "chunks_embedding_hnsw on " + chunks
                        + " using hnsw (embedding vector_cosine_ops) with (m = " + hnswM
                        + ", ef_construction = " + hnswEfConstruction + ")");
            }
            return List.copyOf(ddl);
        }

        /**
         * Opens the index: runs the DDL when {@link #createSchema} is on, then checks the tables
         * exist with the requested dimensions.
         *
         * @throws IllegalArgumentException when the chunks table has another vector dimension
         * @throws IllegalStateException    when the tables are missing and {@code createSchema} is off
         * @throws PgChunkIndexException    on database errors
         */
        public PgChunkIndex build() {
            requireDimensions();
            if (hnswEfConstruction < 2 * hnswM) {
                throw new IllegalArgumentException("hnsw ef_construction (" + hnswEfConstruction
                        + ") must be >= 2 * m (" + hnswM + ")");
            }
            return new PgChunkIndex(this);
        }

        private void requireDimensions() {
            if (dimensions == 0) {
                throw new IllegalStateException("dimensions is required");
            }
        }

        private String qualified(String table) {
            String name = tablePrefix + table;
            return schema == null ? name : schema + "." + name;
        }

        private static String identifier(String value, String what, int maxLength) {
            if (value.length() > maxLength || !IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException(what + " must match " + IDENTIFIER.pattern()
                        + " and be at most " + maxLength + " characters: " + value);
            }
            return value;
        }
    }
}
