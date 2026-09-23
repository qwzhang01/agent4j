package io.github.qwzhang01.agent.memory.store;

import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared {@link MemoryStore} contract against a real PostgreSQL
 * (zonky embedded), plus the two ledger guarantees only a database can give:
 * the partial unique index rejection and the atomic supersede rollback.
 * <p>
 * Gated behind {@code AGENT4J_PG_TEST=true} because the embedded Postgres
 * binary download is heavy for daily CI; the default build still proves the
 * port contract through {@link InMemoryMemoryStoreContractTest}.
 */
@EnabledIfEnvironmentVariable(named = "AGENT4J_PG_TEST", matches = "true")
class PgMemoryStoreContractTest extends MemoryStoreContractTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static final AtomicInteger TABLE_SEQ = new AtomicInteger();

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        postgres.close();
    }

    @Override
    protected MemoryStore newStore() {
        // Fresh table per test: full isolation without TRUNCATE bookkeeping.
        return new PgMemoryStore(dataSource, "agent_memory_it_" + TABLE_SEQ.incrementAndGet(), true);
    }


    @Test
    void partialUniqueIndex_rejectsSecondActiveSameSubject() {
        store.write(entry("user:pi", "home-city", "Shenzhen", MemoryStatus.ACTIVE, T0));

        // The in-memory store silently accepts this drift; the ledger must not.
        assertThrows(IllegalStateException.class,
                () -> store.write(entry("user:pi", "home-city", "Shanghai", MemoryStatus.ACTIVE, T1)));

        assertEquals("Shenzhen",
                store.findActiveBySubject("user:pi", "home-city").orElseThrow().content(),
                "the first ACTIVE line survives; the racing loser surfaces loudly");
    }

    @Test
    void supersede_uniqueViolationMidMove_rollsBackTheClose() throws Exception {
        PgMemoryStore pgStore = new PgMemoryStore(dataSource, "agent_memory_rb", true);
        MemoryEntry old = pgStore.write(new MemoryEntry(null, "user:rb", MemoryType.FACT,
                "home-city", "Shenzhen", 0.9,
                MemoryProvenance.userSaid("t", "r1", T0), MemoryStatus.ACTIVE, T0, null));

        // Out-of-band racing writer, committed between our findActiveBySubject
        // snapshot and our supersede move: it closed the old line (with ITS OWN
        // stamps, T0/T0) and inserted its own ACTIVE replacement. The partial
        // unique index makes two ACTIVE lines unreachable, so a real racer must
        // close before inserting — this is exactly that sequence.
        racerMove("agent_memory_rb", old.id());

        // Our move replays from the stale snapshot: re-close the old line with
        // OUR stamps (T1/T2), then insert our replacement — which hits the
        // unique violation on the racer's ACTIVE slot.
        MemoryEntry replacement = new MemoryEntry(null, "user:rb", MemoryType.FACT,
                "home-city", "Shanghai", 0.9,
                MemoryProvenance.userSaid("t", "r2", T1), MemoryStatus.ACTIVE, T1, null);

        assertThrows(IllegalStateException.class, () -> pgStore.supersede(
                old.closedAs(MemoryStatus.HISTORICAL, T1, T2), replacement));

        // Rollback proof: the old line keeps the RACER's close stamps (T0/T0),
        // not ours (T1/T2) — our half-applied close was rolled back together
        // with the failed insert.
        MemoryEntry after = pgStore.findById(old.id()).orElseThrow();
        assertEquals(MemoryStatus.HISTORICAL, after.status());
        assertEquals(T0, after.validAt(), "racer's stamps survive; ours were rolled back");
        assertEquals(T0, after.invalidAt());
        // The racer's line keeps the ACTIVE slot; ours never landed.
        assertTrue(pgStore.findById("rogue-1").isPresent());
        assertEquals("racer's line",
                pgStore.findActiveBySubject("user:rb", "home-city").orElseThrow().content());
    }

    /**
     * Simulates the racing writer's committed move: close the old line with
     * T0/T0 stamps and insert an ACTIVE replacement — in one committed
     * transaction, out-of-band of the store under test.
     */
    private void racerMove(String targetTable, String oldId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement close = c.prepareStatement(
                     "UPDATE " + targetTable + " SET status = ?, valid_at = ?, invalid_at = ? WHERE id = ?");
             PreparedStatement insert = c.prepareStatement(
                     "INSERT INTO " + targetTable
                             + " (id, scope, type, subject, content, importance, provenance, status, created_at)"
                             + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            c.setAutoCommit(false);
            close.setString(1, MemoryStatus.HISTORICAL.name());
            close.setObject(2, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
            close.setObject(3, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
            close.setString(4, oldId);
            close.executeUpdate();
            insert.setString(1, "rogue-1");
            insert.setString(2, "user:rb");
            insert.setString(3, MemoryType.FACT.name());
            insert.setString(4, "home-city");
            insert.setString(5, "racer's line");
            insert.setDouble(6, 0.5);
            insert.setString(7, "{\"sourceType\":\"USER_SAID\",\"actor\":\"racer\",\"at\":\"2026-09-03T00:00:00Z\"}");
            insert.setString(8, MemoryStatus.ACTIVE.name());
            insert.setObject(9, OffsetDateTime.ofInstant(T2, ZoneOffset.UTC));
            insert.executeUpdate();
            c.commit();
            c.setAutoCommit(true);
        }
    }
}
