package io.github.qwzhang01.agent.core.run;

/**
 * Thrown at an execution boundary when the run's cancellation token fired
 * . This is a control-flow signal, not a business failure:
 * catching code records {@link FailureKind#CANCELLED}, never
 * {@link FailureKind#MODEL_FAILURE} or free text like "tool failed".
 */
public final class RunCancelledException extends RuntimeException {

    public RunCancelledException(String message) {
        super(message);
    }
}
