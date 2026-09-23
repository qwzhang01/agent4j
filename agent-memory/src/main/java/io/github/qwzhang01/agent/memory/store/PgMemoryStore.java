package io.github.qwzhang01.agent.memory.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryLifecycle;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL ledger implementation of {@link MemoryStore} — memory roadmap step 4:
 * the persistent single-source-of-truth account, with two database-level
 * guarantees an in-process store can only promise in javadoc:
 * <ol>
 *   <li><b>One ACTIVE line per (scope, subject).</b> A partial unique index
 *       {@code UNIQUE (scope, subject) WHERE status = 'ACTIVE'} turns the
 *       reconciliation invariant into a physical constraint: a racing second
 *       ACTIVE write for the same subject surfaces as a unique-violation
 *       exception instead of silently drifting into two ACTIVE entries.</li>
 *   <li><b>Atomic supersede.</b> {@link #supersede} applies close-old +
 *       write-new in ONE transaction. A failure midway (for example the unique
 *       index above rejecting the new line after a racing writer won the slot)
 *       rolls the close back, so the ledger can never be left with zero ACTIVE
 *       lines for a subject.</li>
 * </ol>
 * <p>
 * <b>Wiring.</b> Plain JDBC over a host-supplied {@link DataSource}: no
 * connection pool, no ORM, no framework-owned migration tooling. Every single
 * operation borrows one connection and returns it; {@link #supersede} holds one
 * connection for its two statements. The store is stateless and thread-safe
 * as long as the underlying DataSource is.
 * <p>
 * <b>Schema management.</b> By default the constructor provisions the schema
 * idempotently ({@code CREATE TABLE IF NOT EXISTS} + both indexes), safe to
 * run concurrently. Hosts with a DBA-managed schema pass
 * {@code ensureSchema=false} and apply the DDL in the class javadoc of
 * {@link MemoryStore} deployments themselves.
 * <p>
 * <b>Parity with {@link InMemoryMemoryStore}.</b> Every method mirrors the
 * in-process reference semantics: scope isolation in {@code query}, the
 * ACTIVE-only default view with explicit status opt-in, lazy TTL filtering at
 * retrieval time, newest-first ordering, store-assigned ids on write, and
 * {@code write()} with an existing id <b>upserts</b> ({@code INSERT ... ON
 * CONFLICT (id) DO UPDATE}) exactly like a map put — which also lets the admin
 * approve transition (close old line + activate an existing PENDING row) ride
 * the same atomic {@link #supersede} verb.
 * <p>
 * Two deliberate strictness deltas, both ledger-protective and documented here:
 * scope / type / subject / content / status / created_at are NOT NULL (degenerate
 * null writes are rejected at the database boundary), and the partial unique
 * index rejects concurrent same-subject ACTIVE writes that the in-memory store
 * would silently accept.
 * <p>
 * <b>Embedding storage.</b> The vector is stored as a JSON float array in a
 * text column, not a pgvector column: the read path ranks candidates in Java
 * through {@code HybridRankingStrategy}, so an ANN index would buy nothing
 * today — and a schema-pinned vector dimension would break embedding-model
 * swaps. Upgrade path when the candidate pool grows past Java-side ranking:
 * {@code ALTER TABLE ... ADD COLUMN embedding_vec vector(<dim>)}, backfill from
 * the JSON column, add an ANN index, and give the port a vector-search method.
 * The ledger never needs to change for that step.
 * <p>
 * Timestamps bind as {@code TIMESTAMPTZ} via {@link OffsetDateTime} (UTC);
 * PostgreSQL stores microsecond precision. Enums bind as their {@code name()}
 * strings and read back with strict {@code valueOf} — a renamed enum value on
 * old rows surfaces as a loud read error rather than silent data loss.
 */
public class PgMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(PgMemoryStore.class);

    /** Single JSON mapper for the provenance object and the embedding vector. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Table names are interpolated into SQL strings; only plain identifiers pass. */
    private static final Pattern VALID_TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static final String DEFAULT_TABLE = "agent_memory_entries";

    private static final String SELECT_COLUMNS =
            "id, scope, type, subject, content, importance, provenance, status, "
                    + "created_at, expire_at, due_at, lifecycle, embedding, "
                    + "valid_from, valid_at, invalid_at";

    private final DataSource dataSource;
    private final String table;

    /**
     * Ledger on the default table, schema auto-provisioned (idempotent).
     */
    public PgMemoryStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE, true);
    }

    /**
     * @param dataSource  host-supplied pool (framework never opens connections itself)
     * @param table       ledger table name (plain SQL identifier)
     * @param ensureSchema {@code true} = create table + indexes if absent (safe
     *                     to run concurrently); {@code false} = DBA-managed schema
     */
    public PgMemoryStore(DataSource dataSource, String table, boolean ensureSchema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = Objects.requireNonNull(table, "table");
        if (!VALID_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("Illegal table name: " + table);
        }
        if (ensureSchema) {
            ensureSchema();
        }
    }


    @Override
    public MemoryEntry write(MemoryEntry entry) {
        MemoryEntry stored = normalize(entry);
        try (Connection c = dataSource.getConnection()) {
            doInsert(c, stored);
            return stored;
        } catch (SQLException e) {
            throw new IllegalStateException("Write failed on " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<MemoryEntry> query(MemoryQuery query) {
        Instant now = Instant.now();
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        // Scope isolation: only entries in the explicitly requested scopes.
        where.add("scope = ANY(?)");
        params.add(query.scopes().toArray(new String[0]));

        // Default view is ACTIVE-only; explicit statuses opt into e.g. HISTORICAL.
        List<MemoryStatus> statuses = (query.statuses() == null || query.statuses().isEmpty())
                ? List.of(MemoryStatus.ACTIVE)
                : query.statuses();
        where.add("status = ANY(?)");
        params.add(statuses.stream().map(Enum::name).toArray(String[]::new));

        // TTL: lazily filter expired entries, same as the in-memory reference.
        where.add("(expire_at IS NULL OR expire_at > ?)");
        params.add(now);

        if (query.type() != null) {
            where.add("type = ?");
            params.add(query.type().name());
        }
        if (query.subject() != null && !query.subject().isBlank()) {
            where.add("subject = ?");
            params.add(query.subject());
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.add("LOWER(content) LIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(query.keyword().toLowerCase(Locale.ROOT)) + "%");
        }
        if (query.hasDueWindow()) {
            // Entries without a due time never match a due window.
            where.add("due_at IS NOT NULL");
            if (query.dueFrom() != null) {
                where.add("due_at >= ?");
                params.add(query.dueFrom());
            }
            if (query.dueTo() != null) {
                where.add("due_at <= ?");
                params.add(query.dueTo());
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(" FROM ").append(table)
                .append(" WHERE ").append(String.join(" AND ", where))
                .append(" ORDER BY created_at DESC, id ASC");
        if (query.limit() > 0) {
            sql.append(" LIMIT ?");
            params.add(query.limit());
        }

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bind(ps, params, c);
            try (ResultSet rs = ps.executeQuery()) {
                List<MemoryEntry> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readEntry(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed on " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<MemoryEntry> findActiveBySubject(String scope, String subject) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + table
                + " WHERE scope = ? AND subject = ? AND status = 'ACTIVE'"
                + " AND (expire_at IS NULL OR expire_at > ?)"
                + " ORDER BY created_at DESC LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, scope);
            ps.setString(2, subject);
            ps.setObject(3, OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readEntry(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("findActiveBySubject failed on " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public MemoryEntry update(MemoryEntry entry) {
        try (Connection c = dataSource.getConnection()) {
            int rows = doUpdate(c, entry);
            if (rows == 0) {
                throw new IllegalArgumentException("Cannot update non-existent entry: " + entry.id());
            }
            return entry;
        } catch (SQLException e) {
            throw new IllegalStateException("Update failed on " + table + ": " + e.getMessage(), e);
        }
    }

    /**
     * The supersede ledger move in ONE transaction: close the old line, then
     * write the replacement. A unique-violation on the new line (a racing
     * writer already holds the ACTIVE slot for this subject) rolls the close
     * back — the old line stays open, the ledger never loses its only ACTIVE
     * entry. The store-assigned id / createdAt defaults of {@link #write}
     * apply to {@code newEntry} exactly as in a plain write.
     */
    @Override
    public MemoryEntry supersede(MemoryEntry closedOld, MemoryEntry newEntry) {
        MemoryEntry stored = normalize(newEntry);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int rows = doUpdate(c, closedOld);
                if (rows == 0) {
                    throw new IllegalArgumentException("Cannot update non-existent entry: " + closedOld.id());
                }
                doInsert(c, stored);
                c.commit();
                log.debug("Atomic supersede on {}: closed {} and wrote {}", table, closedOld.id(), stored.id());
                return stored;
            } catch (SQLException | RuntimeException e) {
                safeRollback(c, e);
                if (e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Supersede failed on " + table + ": " + e.getMessage(), e);
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // best-effort restore; the connection is about to be closed
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Supersede failed on " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<MemoryEntry> findById(String id) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + table + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readEntry(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("findById failed on " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String id) {
        String sql = "DELETE FROM " + table + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Delete failed on " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<MemoryEntry> listByScope(String scope) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + table
                + " WHERE scope = ? ORDER BY created_at DESC, id ASC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                List<MemoryEntry> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readEntry(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("listByScope failed on " + table + ": " + e.getMessage(), e);
        }
    }


    private void ensureSchema() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                    id          TEXT PRIMARY KEY,
                    scope       TEXT NOT NULL,
                    type        TEXT NOT NULL,
                    subject     TEXT NOT NULL,
                    content     TEXT NOT NULL,
                    importance  DOUBLE PRECISION NOT NULL,
                    provenance  TEXT,
                    status      TEXT NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL,
                    expire_at   TIMESTAMPTZ,
                    due_at      TIMESTAMPTZ,
                    lifecycle   TEXT,
                    embedding   TEXT,
                    valid_from  TIMESTAMPTZ,
                    valid_at    TIMESTAMPTZ,
                    invalid_at  TIMESTAMPTZ
                );
                CREATE UNIQUE INDEX IF NOT EXISTS %s_active_subject
                    ON %s (scope, subject) WHERE status = 'ACTIVE';
                CREATE INDEX IF NOT EXISTS %s_scope_created
                    ON %s (scope, created_at DESC);
                """.formatted(table, table, table, table, table);
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            st.execute(ddl);
            log.info("Ledger schema ready on table {}", table);
        } catch (SQLException e) {
            throw new IllegalStateException("Schema init failed for " + table + ": " + e.getMessage(), e);
        }
    }

    /**
     * Store-assigned id (when null) and createdAt (when null), mirroring
     * {@link InMemoryMemoryStore#write}.
     */
    private static MemoryEntry normalize(MemoryEntry entry) {
        String id = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
        Instant createdAt = entry.createdAt() != null ? entry.createdAt() : Instant.now();
        if (id.equals(entry.id()) && createdAt.equals(entry.createdAt())) {
            return entry;
        }
        return new MemoryEntry(id, entry.scope(), entry.type(), entry.subject(), entry.content(),
                entry.importance(), entry.provenance(), entry.status(), createdAt, entry.expireAt(),
                entry.dueAt(), entry.lifecycle(), entry.embedding(), entry.validFrom(),
                entry.validAt(), entry.invalidAt());
    }

    /** Upsert on id — the SQL shape of the in-memory map put. */
    private void doInsert(Connection c, MemoryEntry e) throws SQLException {
        String sql = "INSERT INTO " + table + " (" + SELECT_COLUMNS + ") VALUES ("
                + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (id) DO UPDATE SET"
                + " scope = EXCLUDED.scope, type = EXCLUDED.type, subject = EXCLUDED.subject,"
                + " content = EXCLUDED.content, importance = EXCLUDED.importance,"
                + " provenance = EXCLUDED.provenance, status = EXCLUDED.status,"
                + " created_at = EXCLUDED.created_at, expire_at = EXCLUDED.expire_at,"
                + " due_at = EXCLUDED.due_at, lifecycle = EXCLUDED.lifecycle,"
                + " embedding = EXCLUDED.embedding, valid_from = EXCLUDED.valid_from,"
                + " valid_at = EXCLUDED.valid_at, invalid_at = EXCLUDED.invalid_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, e.id());
            bindBody(ps, 2, e);
            ps.executeUpdate();
        }
    }

    private int doUpdate(Connection c, MemoryEntry e) throws SQLException {
        String sql = "UPDATE " + table + " SET"
                + " scope = ?, type = ?, subject = ?, content = ?, importance = ?,"
                + " provenance = ?, status = ?, created_at = ?, expire_at = ?, due_at = ?,"
                + " lifecycle = ?, embedding = ?, valid_from = ?, valid_at = ?, invalid_at = ?"
                + " WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindBody(ps, 1, e);
            ps.setString(16, e.id());
            return ps.executeUpdate();
        }
    }

    /** Binds the 15 non-id columns, starting at {@code first}. */
    private static void bindBody(PreparedStatement ps, int first, MemoryEntry e) throws SQLException {
        ps.setString(first, e.scope());
        ps.setString(first + 1, e.type().name());
        ps.setString(first + 2, e.subject());
        ps.setString(first + 3, e.content());
        ps.setDouble(first + 4, e.importance());
        ps.setString(first + 5, e.provenance() == null ? null : toJson(e.provenance()));
        ps.setString(first + 6, e.status().name());
        setInstant(ps, first + 7, e.createdAt());
        setInstant(ps, first + 8, e.expireAt());
        setInstant(ps, first + 9, e.dueAt());
        ps.setString(first + 10, e.lifecycle() == null ? null : e.lifecycle().name());
        ps.setString(first + 11, e.embedding() == null ? null : toJson(e.embedding()));
        setInstant(ps, first + 12, e.validFrom());
        setInstant(ps, first + 13, e.validAt());
        setInstant(ps, first + 14, e.invalidAt());
    }

    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        ps.setObject(index, value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    /** Binds mixed params: String[] as text arrays, Instant as UTC timestamptz, int as LIMIT. */
    private static void bind(PreparedStatement ps, List<Object> params, Connection c) throws SQLException {
        int i = 1;
        for (Object p : params) {
            if (p instanceof String[] arr) {
                Array sqlArray = c.createArrayOf("text", arr);
                ps.setArray(i, sqlArray);
            } else if (p instanceof Instant instant) {
                ps.setObject(i, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
            } else if (p instanceof Integer n) {
                ps.setInt(i, n);
            } else {
                ps.setString(i, (String) p);
            }
            i++;
        }
    }

    private MemoryEntry readEntry(ResultSet rs) throws SQLException {
        return new MemoryEntry(
                rs.getString("id"),
                rs.getString("scope"),
                MemoryType.valueOf(rs.getString("type")),
                rs.getString("subject"),
                rs.getString("content"),
                rs.getDouble("importance"),
                readProvenance(rs.getString("provenance")),
                MemoryStatus.valueOf(rs.getString("status")),
                readInstant(rs, "created_at"),
                readInstant(rs, "expire_at"),
                readInstant(rs, "due_at"),
                readLifecycle(rs.getString("lifecycle")),
                readEmbedding(rs.getString("embedding")),
                readInstant(rs, "valid_from"),
                readInstant(rs, "valid_at"),
                readInstant(rs, "invalid_at"));
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    private static MemoryLifecycle readLifecycle(String raw) {
        return raw == null ? null : MemoryLifecycle.valueOf(raw);
    }

    private static MemoryProvenance readProvenance(String json) {
        return json == null ? null : fromJson(json, MemoryProvenance.class);
    }

    private static float[] readEmbedding(String json) {
        return json == null ? null : fromJson(json, float[].class);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed: " + e.getMessage(), e);
        }
    }

    private static <T> T fromJson(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse failed: " + e.getMessage(), e);
        }
    }

    /** Escapes LIKE wildcards so a keyword matches literally, like String.contains. */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void safeRollback(Connection c, Exception cause) {
        try {
            c.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
