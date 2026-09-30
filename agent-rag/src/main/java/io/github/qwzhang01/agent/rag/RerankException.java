package io.github.qwzhang01.agent.rag;

/** Rerank provider failure (network, timeout, non-2xx, malformed body). */
public class RerankException extends RuntimeException {

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
