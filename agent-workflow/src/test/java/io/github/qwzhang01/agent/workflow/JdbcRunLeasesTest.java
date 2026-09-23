package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunLeases;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunLeases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  {@link JdbcRunLeases} must reproduce the in-memory registry's
 * four rules exactly — single winner, TTL expiry/takeover, heartbeat renew,
 * holder-only release — because the DurableRunManager's cross-instance
 * safety leans on them.
 */
class JdbcRunLeasesTest {

    static {
        // DriverManager's ServiceLoader discovery is racy under in-process
        // (forkCount=0) runs where module classloaders share one JVM; forcing
        // H2 class init self-registers the driver regardless of SPI timing.
        org.h2.Driver.load();
    }

    private Connection conn;
    private RunLeases leases;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:leases_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        leases = new JdbcRunLeases(conn);
        ((JdbcRunLeases) leases).initialize();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    void exactlyOneWinnerWhenFree() {
        assertTrue(leases.tryAcquire("run-1", "worker-a", 60_000));
        assertFalse(leases.tryAcquire("run-1", "worker-b", 60_000),
                "second worker must lose while the holder is live");
        Optional<String> holder = leases.holder("run-1");
        assertTrue(holder.isPresent());
        assertEquals("worker-a", holder.get());
        assertTrue(leases.isHeld("run-1"));
    }

    @Test
    void ttlZeroMeansNeverExpires() {
        assertTrue(leases.tryAcquire("run-eternal", "worker-a", 0));
        // Even "after expiry" the 0-TTL lease stays held.
        assertTrue(leases.isHeld("run-eternal"));
        assertFalse(leases.tryAcquire("run-eternal", "worker-b", 60_000));
        // Renew of a 0-TTL lease keeps it 0 (never expires).
        assertTrue(leases.renew("run-eternal", "worker-a", 0));
        assertTrue(leases.isHeld("run-eternal"));
    }

    @Test
    void expiredLeaseIsTakeOverable() {
        assertTrue(leases.tryAcquire("run-2", "worker-a", 1));
        sleep(20);
        assertTrue(leases.isHeld("run-2") == false,
                "expired lease must not report held");
        assertTrue(leases.tryAcquire("run-2", "worker-b", 60_000),
                "expired lease must be take-overable");
        assertEquals("worker-b", leases.holder("run-2").orElseThrow());
    }

    @Test
    void renewExtendsAndOnlyHolderRenews() {
        assertTrue(leases.tryAcquire("run-3", "worker-a", 200));
        // Non-holder cannot renew.
        assertFalse(leases.renew("run-3", "worker-b", 60_000));
        // Holder renews: the lease survives past the original TTL.
        assertTrue(leases.renew("run-3", "worker-a", 60_000));
        sleep(250);
        assertTrue(leases.isHeld("run-3"), "renewed lease must survive the original TTL");
        assertTrue(leases.renew("run-3", "worker-a", 60_000),
                "still-live lease can renew again");
    }

    @Test
    void renewFailsOnceExpired() {
        assertTrue(leases.tryAcquire("run-4", "worker-a", 1));
        sleep(20);
        assertFalse(leases.renew("run-4", "worker-a", 60_000),
                "renew after expiry must fail: ownership is lost");
    }

    @Test
    void holderOnlyRelease() {
        assertTrue(leases.tryAcquire("run-5", "worker-a", 60_000));
        assertFalse(leases.release("run-5", "worker-b"),
                "non-holder release must fail");
        assertTrue(leases.isHeld("run-5"));
        assertTrue(leases.release("run-5", "worker-a"));
        assertFalse(leases.isHeld("run-5"));
        // Free again: another worker can acquire.
        assertTrue(leases.tryAcquire("run-5", "worker-b", 60_000));
    }

    @Test
    void releaseAfterTakeoverByOriginalHolderFails() {
        assertTrue(leases.tryAcquire("run-6", "worker-a", 1));
        sleep(20);
        assertTrue(leases.tryAcquire("run-6", "worker-b", 60_000));
        // worker-a's lease was taken over: its release must fail.
        assertFalse(leases.release("run-6", "worker-a"));
        assertEquals("worker-b", leases.holder("run-6").orElseThrow());
    }

    @Test
    void unknownRunQueriesAreEmptyNotLoud() {
        assertFalse(leases.isHeld("run-ghost"));
        assertTrue(leases.holder("run-ghost").isEmpty());
        assertFalse(leases.release("run-ghost", "worker-a"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
