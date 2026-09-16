package io.github.qwzhang01.agent.mcp;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stage 6.2: host-managed authentication adapter for remote MCP servers.
 * <p>
 * Roadmap: "OAuth 或宿主认证适配器". The split the framework draws: the
 * HOST owns credentials (vault, env, SSO session); the transport only
 * CONSUMES a ready value. Two shapes ship:
 * <ul>
 *   <li>{@link #staticToken} — a fixed bearer/static header value. Good
 *       for gateway-issued keys; the value never changes per call.</li>
 *   <li>{@link #refreshable} — a {@link Supplier} the transport re-reads on
 *       every request. Good for short-lived tokens: the supplier may hit a
 *       vault or refresh an OAuth token out-of-band. It is called on the
 *       request path, so implementations should cache internally.</li>
 * </ul>
 * <p>
 * What is deliberately NOT here: a full OAuth client (authorization-code
 * dance, PKCE, token endpoint calls). That is a host concern — the
 * framework gets a fresh value from the supplier and stays out of the
 * credential business. Honest gap, tracked in notes/harness-gap.
 *
 * @param headerName   the header to set (default "Authorization")
 * @param valueSupplier called per request; must never return credentials
 *                      into logs — only into the header
 */
public record McpAuthConfig(String headerName, Supplier<String> valueSupplier) {

    private static final String DEFAULT_HEADER = "Authorization";

    public McpAuthConfig {
        if (headerName == null || headerName.isBlank()) {
            headerName = DEFAULT_HEADER;
        }
        Objects.requireNonNull(valueSupplier, "valueSupplier must not be null");
    }

    /** Fixed bearer token. */
    public static McpAuthConfig bearer(Supplier<String> tokenSupplier) {
        return new McpAuthConfig("Authorization", () -> {
            String token = tokenSupplier.get();
            return token == null || token.isBlank() ? null : "Bearer " + token;
        });
    }

    /** Fixed raw header value under a custom header (e.g. x-api-key). */
    public static McpAuthConfig staticToken(String headerName, String token) {
        return new McpAuthConfig(headerName, () -> token);
    }

    /** Supplier-read value; re-read per request for short-lived tokens. */
    public static McpAuthConfig refreshable(String headerName, Supplier<String> supplier) {
        return new McpAuthConfig(headerName, supplier);
    }

    /** The header value for this request, or null to send no auth header. */
    public String headerValue() {
        String value = valueSupplier.get();
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("Bearer ") || !DEFAULT_HEADER.equals(headerName)
                ? value
                : "Bearer " + value;
    }

    /** Whether to apply this config's header to the request builder. */
    public boolean applies() {
        return headerValue() != null;
    }
}
