package io.github.qwzhang01.agent.rag.pipeline;

/** A pipeline call that cannot produce any answer, e.g. the index itself is unavailable. */
public class RagException extends RuntimeException {

    public RagException(String message, Throwable cause) {
        super(message, cause);
    }
}
