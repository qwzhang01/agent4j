package io.github.qwzhang01.agent.rag.pgvector;

/** A database error inside {@link PgChunkIndex}; the cause is the underlying {@link java.sql.SQLException}. */
public class PgChunkIndexException extends RuntimeException {

    public PgChunkIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
