package io.github.qwzhang01.agent.scheduler;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Stage 8.3: PostgreSQL integration profile base class (scheduler copy of
 * the workflow one - module-local so no cross-module test dependency is
 * introduced; agent-workflow cannot depend on agent-scheduler without a
 * reactor cycle).
 * <p>
 * Semantics identical to {@code io.github.qwzhang01.agent.workflow.PostgresIT}:
 * skip-by-assumption when PG is not reachable, one random schema per test,
 * drop-on-close. See that class for the connection-resolution order.
 */
class PostgresIT {

    /** Test tag: surefire filters {@code -Dgroups=postgres} on this. */
    static final String TAG = "postgres";

    private static volatile boolean availabilityChecked;
    private static volatile boolean available;

    @BeforeEach
    void requirePostgres() {
        if (!availabilityChecked) {
            synchronized (PostgresIT.class) {
                if (!availabilityChecked) {
                    available = probe();
                    availabilityChecked = true;
                }
            }
        }
        Assumptions.assumeTrue(available,
                "PostgreSQL not reachable - postgres-tagged tests are skipped. "
                        + "Start one and set AGENT4J_IT_PG_URL (or HOST/PORT/DB) to run them.");
    }

    /** One connection per test method: the store under test owns it. */
    Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url(), user(), password());
    }

    /** Fresh schema per test; dropped on close. The schema is the isolation unit. */
    SchemaHandle freshSchema(Connection conn) throws SQLException {
        String name = "it_" + Long.toUnsignedString(System.nanoTime(), 36);
        try (var st = conn.createStatement()) {
            st.execute("CREATE SCHEMA " + name);
            st.execute("SET search_path TO " + name);
        }
        return new SchemaHandle(conn, name);
    }

    /** Drops the schema on close; swallowing errors keeps test cleanup quiet. */
    static final class SchemaHandle implements AutoCloseable {
        private final Connection conn;
        private final String name;

        SchemaHandle(Connection conn, String name) {
            this.conn = conn;
            this.name = name;
        }

        @Override
        public void close() {
            try (var st = conn.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
            } catch (SQLException swallowed) {
                // Cleanup noise must not fail a passing test.
            }
        }
    }

    private static boolean probe() {
        String url = System.getenv("AGENT4J_IT_PG_URL");
        String explicit = System.getProperty("agent4j.it.postgres.enabled");
        boolean enabled = url != null
                || "true".equalsIgnoreCase(explicit)
                || System.getenv("AGENT4J_IT_PG_HOST") != null;
        if (!enabled) {
            return false;
        }
        try (Connection c = DriverManager.getConnection(url(), user(), password());
             var st = c.createStatement()) {
            st.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String url() {
        String url = System.getenv("AGENT4J_IT_PG_URL");
        if (url != null && !url.isBlank()) {
            return url;
        }
        String host = env("AGENT4J_IT_PG_HOST", "localhost");
        String port = env("AGENT4J_IT_PG_PORT", "5432");
        String db = env("AGENT4J_IT_PG_DB", "agent4j_it");
        return "jdbc:postgresql://" + host + ":" + port + "/" + db;
    }

    private static String user() {
        return env("AGENT4J_IT_PG_USER", System.getProperty("user.name"));
    }

    private static String password() {
        return env("AGENT4J_IT_PG_PASSWORD", "");
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
