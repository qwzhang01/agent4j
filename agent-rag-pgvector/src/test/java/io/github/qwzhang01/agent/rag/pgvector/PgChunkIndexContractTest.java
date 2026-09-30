package io.github.qwzhang01.agent.rag.pgvector;

import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.index.ChunkIndexContractTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs {@link ChunkIndexContractTest} against PostgreSQL + pgvector.
 * Skips when {@code RAG_PG_TEST_URL} (default local test database) is unreachable.
 */
class PgChunkIndexContractTest extends ChunkIndexContractTest {

    private static final String URL = System.getenv().getOrDefault(
            "RAG_PG_TEST_URL", "jdbc:postgresql://127.0.0.1:55432/rag_test_fw?user=rag");

    private static DataSource dataSource;
    private final String prefix = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";

    @BeforeAll
    static void requireDatabase() {
        try (Connection connection = DriverManager.getConnection(URL)) {
            assumeTrue(connection.isValid(2), "PostgreSQL connection is not valid: " + URL);
        } catch (SQLException e) {
            assumeTrue(false, "PostgreSQL with pgvector is required at " + URL + ": " + e.getMessage());
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(URL);
        dataSource = source;
    }

    @Override
    protected ChunkIndex open(int dimensions) {
        return PgChunkIndex.builder(dataSource)
                .dimensions(dimensions)
                .tablePrefix(prefix)
                .createExtension(false)
                .build();
    }

    @AfterEach
    void dropTables() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists " + prefix + "chunks");
            statement.execute("drop table if exists " + prefix + "docs");
        }
    }
}
