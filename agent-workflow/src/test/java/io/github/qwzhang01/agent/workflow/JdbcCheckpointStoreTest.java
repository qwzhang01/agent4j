package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.Checkpoint;
import io.github.qwzhang01.agent.workflow.runtime.FileCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.RunState;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcCheckpointStore;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link JdbcCheckpointStore} : the
 * checkpoint payload must round-trip through the database with the same
 * semantics as {@link FileCheckpointStore} (one codec, two transports),
 * because instance B's resume depends on instance A's pause.
 * <p>
 * H2 in-memory with {@code DB_CLOSE_DELAY=-1} keeps the schema alive for
 * the test's lifetime; two stores sharing one connection simulate two
 * runtime instances on one database.
 */
class JdbcCheckpointStoreTest {

    static {
        // DriverManager's ServiceLoader discovery is racy under in-process
        // (forkCount=0) runs where module classloaders share one JVM; forcing
        // H2 class init self-registers the driver regardless of SPI timing.
        org.h2.Driver.load();
    }

    private Connection connection;
    private JdbcCheckpointStore store;
    private JdbcCheckpointStore otherInstance;

    private void open() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:cpstore_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        store = new JdbcCheckpointStore(connection);
        otherInstance = new JdbcCheckpointStore(connection);
        store.initialize();
        otherInstance.initialize(); // idempotent DDL
    }

    @AfterEach
    void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    private Checkpoint sample(String runId, String cursor) {
        StepRecord step = StepRecord.success("charge", 12, 1, "charged ok");
        WorkflowState state = WorkflowState.restore("order-1",
                Map.of("amount", 99, "customer", "seven"), List.of(step));
        return new Checkpoint(Checkpoint.SCHEMA_VERSION, "cp-" + runId, runId,
                "wf", "1.0", "hash-abc", RunState.PAUSED, cursor, state,
                System.currentTimeMillis(), 3, "pending-input", 42L, List.of(step));
    }

    @Test
    void initializeIsIdempotent() throws SQLException {
        open();
        store.initialize();
        store.initialize(); // no exception, no schema drift
        assertTrue(store.listRunIds().isEmpty());
    }

    @Test
    void saveAndLoadRoundTrip() throws SQLException {
        open();
        Checkpoint cp = sample("run-a", "charge");
        String id = store.save(cp);
        assertEquals("cp-run-a", id);

        Optional<Checkpoint> back = otherInstance.load("run-a");
        assertTrue(back.isPresent(), "the other instance must see instance A's pause");
        Checkpoint loaded = back.get();
        assertEquals(cp.checkpointId(), loaded.checkpointId());
        assertEquals(cp.runId(), loaded.runId());
        assertEquals(cp.workflowName(), loaded.workflowName());
        assertEquals(cp.workflowVersion(), loaded.workflowVersion());
        assertEquals(cp.workflowHash(), loaded.workflowHash());
        assertEquals(cp.status(), loaded.status());
        assertEquals(cp.cursor(), loaded.cursor());
        assertEquals(cp.stepsExecuted(), loaded.stepsExecuted());
        assertEquals(cp.pendingInput(), loaded.pendingInput());
        assertEquals(cp.lastEventSeq(), loaded.lastEventSeq());
        assertEquals("order-1", loaded.state().getInput());
        assertEquals(99, loaded.state().getVariables().get("amount"));
        assertEquals("seven", loaded.state().getVariables().get("customer"));
        assertEquals(1, loaded.state().getTrace().size());
        assertEquals("charge", loaded.state().getTrace().get(0).nodeId());
    }

    @Test
    void saveOverwritesLatestPerRun() throws SQLException {
        open();
        store.save(sample("run-b", "node-1"));
        Checkpoint newer = new Checkpoint(Checkpoint.SCHEMA_VERSION, "cp-run-b-2", "run-b",
                "wf", "1.0", "hash-abc", RunState.PAUSED, "node-2",
                WorkflowState.restore("order-2", Map.of(), List.of()),
                System.currentTimeMillis(), 5, null, 7L, List.of());
        store.save(newer);

        // One latest checkpoint per runId (same contract as FileCheckpointStore)
        assertEquals(1, otherInstance.listRunIds().size());
        Checkpoint loaded = otherInstance.load("run-b").orElseThrow();
        assertEquals("cp-run-b-2", loaded.checkpointId());
        assertEquals("node-2", loaded.cursor());
        assertEquals(5, loaded.stepsExecuted());
    }

    @Test
    void loadMissingReturnsEmpty() throws SQLException {
        open();
        assertTrue(otherInstance.load("no-such-run").isEmpty());
    }

    @Test
    void deleteRemovesOnlyTarget() throws SQLException {
        open();
        store.save(sample("run-c", "n1"));
        store.save(sample("run-d", "n1"));
        otherInstance.delete("run-c");
        assertTrue(otherInstance.load("run-c").isEmpty());
        assertTrue(otherInstance.load("run-d").isPresent());
    }

    @Test
    void listRunIdsSorted() throws SQLException {
        open();
        store.save(sample("run-z", "n"));
        store.save(sample("run-a", "n"));
        store.save(sample("run-m", "n"));
        List<String> ids = otherInstance.listRunIds();
        assertEquals(List.of("run-a", "run-m", "run-z"), ids);
    }

    @Test
    void illegalRunIdRejectedBeforeSql() throws SQLException {
        open();
        assertThrows(IllegalArgumentException.class,
                () -> store.save(sample("../escape", "n")));
        assertThrows(IllegalArgumentException.class, () -> store.load("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("bad/slash"));
    }

    @Test
    void schemaRefusalOnNewerVersion() throws SQLException {
        open();
        Checkpoint future = new Checkpoint(Checkpoint.SCHEMA_VERSION + 1, "cp-f", "run-f",
                "wf", "1.0", "hash", RunState.PAUSED, "n",
                WorkflowState.restore("i", Map.of(), List.of()),
                System.currentTimeMillis(), 0, null, 0L, List.of());
        store.save(future);
        // to load it, not silently misparse (same refusal as FileCheckpointStore).
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> otherInstance.load("run-f"));
        assertTrue(ex.getMessage().contains("newer than this runtime understands"),
                "message must name the schema refusal: " + ex.getMessage());
    }

    @Test
    void fileAndJdbcShareCodecSemantics() throws SQLException, java.io.IOException {
        open();
        // must decode to the same blackboard — the codec is shared, not forked.
        Checkpoint cp = sample("run-shared", "charge");
        store.save(cp);

        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cp-shared");
        FileCheckpointStore fileStore = new FileCheckpointStore(dir);
        fileStore.save(cp);

        Checkpoint fromDb = otherInstance.load("run-shared").orElseThrow();
        Checkpoint fromFile = fileStore.load("run-shared").orElseThrow();
        assertEquals(fromFile.cursor(), fromDb.cursor());
        assertEquals(fromFile.state().getInput(), fromDb.state().getInput());
        assertEquals(fromFile.state().getVariables(), fromDb.state().getVariables());
        FileCheckpointStore.deleteRecursively(dir);
    }
}
