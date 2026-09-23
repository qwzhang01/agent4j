package io.github.qwzhang01.agent.core.run;

/**
 * Thrown at an execution boundary when the run's deadline expired
 * . Structured signal: catches record
 * {@link FailureKind#TIMEOUT}, never free text. Deadline expiry is a
 * resource condition, not a business failure.
 */
public final class RunDeadlineException extends RuntimeException {

    public RunDeadlineException(String message) {
        super(message);
    }
}
