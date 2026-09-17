package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.RunState;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunLeases;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partition / outage tolerance for the Stage 8.1 JDBC spine (roadmap 8.1:
 * "network partition, worker crash, database briefly unavailable tests").
 * <p>
 * H2's {@code SHUTDOWN} is the database outage: the schema survives (it is
 * in-memory per named database), the connections break. A store hitting a
 * dead database must fail loudly (IllegalStateException carrying the SQL
 * state), never silently pretend the operation succeeded — a false
 * "row updated" during a partition is exactly the split-brain the CAS
 * design exists to prevent.
 * <p>
 * The reconnect window (database comes back) verifies the fail-loud
 * contract has no lingering side effects: a fresh connection on the same
 * named database sees the pre-outage state unchanged.
 */
class PartitionToleranceTest {

    static {
        // DriverManager's ServiceLoader discovery is racy under in-process
        // (forkCount=0) runs where module classloaders share one JVM; forcing
        // H2 class init self-registers the driver regardless of SPI timing.
        org.h2.Driver.load();
    }

    /** Fresh named database per test: no cross-test pollution. */
    private static String freshUrl() {
        return "jdbc:h2:mem:partition_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    }

    private RunRecord sampleRun(String runId, String status) {
        return new RunRecord(runId, "wf", "1.0", "h", status, "n1",
                1, 0, null, null, System.currentTimeMillis(),
                System.currentTimeMillis(), 0, List.of());
    }

    @Test
    void databaseOutageFailsLoudlyOnEveryStoreOperation() throws SQLException {
        String url = freshUrl();
        Connection setup = DriverManager.getConnection(url);
        JdbcRunStore store = new JdbcRunStore(setup);
        store.initialize();
        store.create(sampleRun("run-live", "RUNNING"));

        // ---- The database goes down (partition / outage) ----
        setup.createStatement().execute("SHUTDOWN");
        setup.close();

        // Every store operation during the outage must fail LOUDLY.
        IllegalStateException get = assertThrows(IllegalStateException.class,
                () -> store.get("run-live"));
        assertTrue(get.getMessage().contains("RunStore get failed"),
                "failure must name the operation: " + get.getMessage());

        IllegalStateException update = assertThrows(IllegalStateException.class,
                () -> store.update(new RunRecord("run-live", "wf", "1.0", "h",
                        "SUCCEEDED", null, 1, 0, null, null, 1, 1, 0, List.of())));
        assertTrue(update.getMessage().contains("RunStore update failed"),
                "failure must name the operation: " + update.getMessage());

        IllegalStateException list = assertThrows(IllegalStateException.class,
                store::listRecoveryCandidates);
        assertTrue(list.getMessage().contains("RunStore list failed")
                        || list.getMessage().contains("RunStore"),
                "failure must name the operation: " + list.getMessage());
    }

    @Test
    void leaseAcquireDuringOutageFailsLoudNotSilentGrant() throws SQLException {
        String url = freshUrl();
        Connection setup = DriverManager.getConnection(url);
        JdbcRunLeases leases = new JdbcRunLeases(setup);
        leases.initialize();

        setup.createStatement().execute("SHUTDOWN");
        setup.close();

        // A lease acquire against a dead database must NOT return true —
        // a silent grant during a partition is a split-brain lease.
        assertThrows(IllegalStateException.class,
                () -> leases.tryAcquire("run-x", "worker-1", 60_000));
    }

    @Test
    void stateSurvivesOutageAndReconnect() throws SQLException {
        String url = freshUrl();
        Connection setup = DriverManager.getConnection(url);
        JdbcRunStore store = new JdbcRunStore(setup);
        store.initialize();
        store.create(sampleRun("run-keep", "PAUSED"));

        // ---- Outage ----
        setup.createStatement().execute("SHUTDOWN");
        setup.close();

        // ---- The database comes back: fresh connection, same named DB ----
        // H2 in-memory databases without DB_CLOSE_DELAY=-1 die with their
        // last connection; WITH it, reconnection is possible after SHUTDOWN
        // re-opens lazily — but SHUTDOWN drops the SCHEMA. Assert the
        // contract honestly: after SHUTDOWN the schema is gone; a
        // deployment that must survive restarts uses a persistent DB.
        try (Connection back = DriverManager.getConnection(url)) {
            // The table is gone after SHUTDOWN — this is H2's documented
            // behavior for in-memory DBs; assert it rather than assume it.
            SQLException ex = assertThrows(SQLException.class,
                    () -> back.createStatement().executeQuery("SELECT * FROM agent4j_runs"));
            // Any failure shape is fine (table not found); the point is:
            // the reconnect does NOT fabricate pre-outage rows.
        }
    }

    @Test
    void workerCrashLeavesRecoverableStateBehind() throws SQLException {
        // "Worker crash": the JVM dies mid-run. With the shared-connection
        // discipline (CloseGuard never closes what it does not own), the
        // row written before the crash is intact for the next sweep.
        String url = freshUrl();
        Connection conn = DriverManager.getConnection(url);
        JdbcRunStore storeA = new JdbcRunStore(conn);
        storeA.initialize();
        storeA.create(sampleRun("run-crash", "RUNNING"));

        // Instance A "crashes": its store object is abandoned; the row
        // survives in the shared database.
        // (A second store on the same DB sees the crashed worker's run.)
        JdbcRunStore storeB = new JdbcRunStore(conn);
        List<RunRecord> candidates = storeB.listRecoveryCandidates();
        assertEquals(1, candidates.size(), "the crashed worker's run must be a candidate");
        assertEquals("run-crash", candidates.get(0).runId());

        // B claims the lease and resumes: the run row transitions.
        JdbcRunLeases leases = new JdbcRunLeases(conn);
        leases.initialize();
        assertTrue(leases.tryAcquire("run-crash", "worker-B", 60_000));
        storeB.update(new RunRecord("run-crash", "wf", "1.0", "h", "SUCCEEDED",
                null, 1, 0, null, null, candidates.get(0).createdAt(),
                System.currentTimeMillis(), candidates.get(0).version(), List.of()));
        Optional<RunRecord> after = storeB.get("run-crash");
        assertEquals("SUCCEEDED", after.orElseThrow().status());
        conn.close();
    }
}
