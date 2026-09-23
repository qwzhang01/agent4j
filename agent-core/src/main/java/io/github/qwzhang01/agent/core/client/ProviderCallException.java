package io.github.qwzhang01.agent.core.client;

import java.time.Duration;
import java.util.Objects;

/**
 * Stage 6.1: provider error taxonomy, one exception carrying everything the
 * recovery machinery needs.
 * <p>
 * Stage 1's {@link ModelException} already fixed the seven-category taxonomy
 * (auth / rate-limit / invalid request / server / network / timeout /
 * unknown) — every concrete client maps HTTP failures onto it. What Stage 1
 * did NOT carry: the provider's <b>Retry-After</b> hint (429 responses tell
 * you how long to wait; the info was dropped), a <b>parse</b> category
 * (garbage JSON from a flaky gateway used to collapse into MODEL_ERROR),
 * and a <b>canceled</b> category (caller-initiated aborts used to look like
 * network noise, and retried — the worst possible outcome).
 * <p>
 * This exception extends the Stage 1 type so every existing catch site,
 * {@code RetryModelClient} policy table, and {@code FallbackModelClient}
 * keep working unchanged. New knobs are additive:
 * <ul>
 *   <li>{@link #retryAfter()} — the provider's wait hint, null when absent.
 *       Retry policies SHOULD respect it when present (they MAY cap it).</li>
 *   <li>{@link #statusCode()} — the HTTP status that triggered the mapping
 *       (0 = not an HTTP failure), for dashboards and alert routing.</li>
 *   <li>{@link #providerName()} — which client threw, for multi-provider
 *       setups and credential-rotation decisions.</li>
 * </ul>
 * <p>
 * New categories extend the enum where Stage 1 left off. The full Stage 6.1
 * taxonomy (roadmap wording "认证、限流、参数、服务端、网络、解析、取消"):
 * AUTH_ERROR(认证), RATE_LIMITED(限流), INVALID_REQUEST(参数),
 * MODEL_ERROR(服务端), NETWORK_ERROR(网络), PARSE_ERROR(解析),
 * CANCELED(取消), TIMEOUT, UNKNOWN.
 */
public class ProviderCallException extends ModelException {

    private static final long serialVersionUID = 1L;

    public enum ProviderErrorCode {
        AUTH_ERROR,
        RATE_LIMITED,
        INVALID_REQUEST,
        MODEL_ERROR,
        NETWORK_ERROR,
        PARSE_ERROR,
        CANCELED,
        TIMEOUT,
        UNKNOWN;

        /**
         * Map a Stage 1 {@link ModelException.ErrorCode} onto the Stage 6.1
         * taxonomy. PARSE_ERROR has no Stage 1 counterpart (it used to be
         * MODEL_ERROR); CANCELED has none either (it used to be
         * NETWORK_ERROR).
         */
        public static ProviderErrorCode fromLegacy(ErrorCode legacy) {
            return switch (legacy) {
                case NETWORK_ERROR -> NETWORK_ERROR;
                case TIMEOUT -> TIMEOUT;
                case RATE_LIMITED -> RATE_LIMITED;
                case AUTH_ERROR -> AUTH_ERROR;
                case INVALID_REQUEST -> INVALID_REQUEST;
                case MODEL_ERROR -> MODEL_ERROR;
                case UNKNOWN -> UNKNOWN;
            };
        }

        /**
         * Fold back onto the Stage 1 taxonomy for pre-Stage-6 catch sites.
         * PARSE_ERROR folds to MODEL_ERROR and CANCELED to NETWORK_ERROR
         * (their closest legacy buckets); everything else maps 1:1.
         */
        public ErrorCode toLegacy() {
            return switch (this) {
                case AUTH_ERROR -> ErrorCode.AUTH_ERROR;
                case RATE_LIMITED -> ErrorCode.RATE_LIMITED;
                case INVALID_REQUEST -> ErrorCode.INVALID_REQUEST;
                case MODEL_ERROR -> ErrorCode.MODEL_ERROR;
                case NETWORK_ERROR -> ErrorCode.NETWORK_ERROR;
                case PARSE_ERROR -> ErrorCode.MODEL_ERROR; // closest legacy bucket
                case CANCELED -> ErrorCode.NETWORK_ERROR;  // closest legacy bucket
                case TIMEOUT -> ErrorCode.TIMEOUT;
                case UNKNOWN -> ErrorCode.UNKNOWN;
            };
        }
    }

    private final ProviderErrorCode providerCode;
    private final Duration retryAfter;
    private final int statusCode;
    private final String providerName;

    /**
     * Full form.
     *
     * @param providerCode Stage 6.1 taxonomy code (required)
     * @param message      human-readable cause (required)
     * @param cause        underlying throwable (nullable)
     * @param retryAfter   provider's Retry-After hint (null = none given)
     * @param statusCode   HTTP status that triggered the mapping (0 = none)
     * @param providerName which client threw (null = "unknown")
     */
    public ProviderCallException(ProviderErrorCode providerCode, String message, Throwable cause,
                                 Duration retryAfter, int statusCode, String providerName) {
        super(providerCode.toLegacy(), message, cause);
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode");
        this.retryAfter = retryAfter;
        this.statusCode = Math.max(0, statusCode);
        this.providerName = providerName != null ? providerName : "unknown";
    }

    /** Compact form without retry hint / status / provider. */
    public ProviderCallException(ProviderErrorCode providerCode, String message) {
        this(providerCode, message, null, null, 0, null);
    }

    /** Compact form with cause. */
    public ProviderCallException(ProviderErrorCode providerCode, String message, Throwable cause) {
        this(providerCode, message, cause, null, 0, null);
    }

    /** Full form for HTTP-mapped failures. */
    public ProviderCallException(ProviderErrorCode providerCode, String message, Throwable cause,
                                 Duration retryAfter, int statusCode) {
        this(providerCode, message, cause, retryAfter, statusCode, null);
    }

    public ProviderErrorCode getProviderCode() {
        return providerCode;
    }

    /** The provider's Retry-After hint, or null when the provider gave none. */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** HTTP status that triggered the mapping, or 0 for non-HTTP failures. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Which client threw this exception ("unknown" when not set). */
    public String getProviderName() {
        return providerName;
    }

    /**
     * Whether this category is worth retrying per the roadmap taxonomy
     * (auth / invalid-request / cancel are terminal; the rest may clear).
     * This mirrors the intent of {@code ClientRetry#shouldRetry} but on the
     * richer taxonomy: notably CANCELED is never retried.
     */
    public boolean isRetryable() {
        return switch (providerCode) {
            case RATE_LIMITED, NETWORK_ERROR, TIMEOUT, MODEL_ERROR, UNKNOWN -> true;
            case AUTH_ERROR, INVALID_REQUEST, PARSE_ERROR, CANCELED -> false;
        };
    }
}
