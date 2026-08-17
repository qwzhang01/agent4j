package io.github.qwzhang01.agent.core.client;

/**
 * Exception thrown when a model call fails.
 * <p>
 * In stage 1 this is a simple unchecked exception.
 * Later stages will add structured error codes (TIMEOUT, RATE_LIMIT, AUTH, etc.)
 * to support retry and fallback logic.
 */
public class ModelException extends RuntimeException {

    private final ErrorCode code;

    public ModelException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ModelException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public enum ErrorCode {
        NETWORK_ERROR,
        TIMEOUT,
        RATE_LIMITED,
        AUTH_ERROR,
        INVALID_REQUEST,
        MODEL_ERROR,
        UNKNOWN
    }
}
