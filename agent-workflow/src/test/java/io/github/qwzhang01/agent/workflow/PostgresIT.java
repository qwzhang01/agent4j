package io.github.qwzhang01.agent.workflow;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 *  PostgreSQL integration profile base class.
 * <p>
 * The JDBC spine's dialect discipline (plain ANSI SQL, H2 tests /
 * PostgreSQL production) gets its proof here: the same contract tests run
 * against a real PostgreSQL when {@code agent4j.it.postgres} is enabled and
 * a database is reachable, and are ASSUMPTION-SKIPPED (not silently passed,
 * not failed) otherwise. The skip is the honest answer on a machine with no
 * PostgreSQL — CI runs with the profile on.
 * <p>
 * Connection resolution order (first hit wins):
 * <ol>
 *   <li>{@code jdbc:postgresql://…} URL from {@code AGENT4J_IT_PG_URL}</li>
 *   <li>{@code host/port/db} from {@code AGENT4J_IT_PG_HOST/PORT/DB}
 *       (defaults {@code localhost:5432/agent4j_it})</li>
 * </ol>
 * User/password come from {@code AGENT4J_IT_PG_USER/PASSWORD} (default: the
 * OS user, no password — local Homebrew PG trusts local socket peers).
 * <p>
 * Each test opens its OWN schema and drops it afterwards: tests never see
 * each other's rows, parallel runs don't collide, and a crashed test leaves
 * nothing behind (schema-level cleanup beats row-level: no leaked rows, no
 * truncate ordering issues).
 */
class PostgresIT {

    /** Test tag: surefire/failsafe filter {@code -Dgroups} on this. */
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

    /**
     * Fresh schema per test: create with a random name, install on the
     * connection's search_path, drop on cleanup. The schema is the isolation
     * unit — tests never share tables.
     */
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
