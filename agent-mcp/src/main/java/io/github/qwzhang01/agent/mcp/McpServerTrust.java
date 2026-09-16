package io.github.qwzhang01.agent.mcp;

import java.util.List;
import java.util.Objects;

/**
 * Stage 6.2: remote-server trust model — an allowlist plus three levels.
 * <p>
 * Roadmap: "远程 MCP Server allowlist 和信任等级". The rule the framework
 * enforces: a remote server must be EXPLICITLY allowed before any
 * connection is attempted. No wildcard, no "connect first, ask later" —
 * remote code/data sources are exactly how supply-chain incidents start.
 * <p>
 * Levels (what the level <em>permits</em>, not what it promises):
 * <ul>
 *   <li>{@code TRUSTED} — full tool surface; tools still flow through the
 *       governance layers (approval/audit/sanitization), trust is about the
 *       SOURCE, not a bypass of Stage 9 governance</li>
 *   <li>{@code RESTRICTED} — connect allowed, but every tool call requires
 *       explicit approval (the governance layer's approval mode is forced
 *       on; unattended runs get nothing from this server)</li>
 *   <li>{@code UNTRUSTED} — not on the allowlist. Not connectable. The
 *       failure is at configuration time, not at call time.</li>
 * </ul>
 */
public record McpServerTrust(
        String serverName,
        String urlPattern,
        TrustLevel level,
        List<String> allowedTools
) {

    /** What the framework permits for this server. */
    public enum TrustLevel { TRUSTED, RESTRICTED, UNTRUSTED }

    public McpServerTrust {
        Objects.requireNonNull(serverName, "serverName must not be null");
        Objects.requireNonNull(level, "level must not be null");
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }

    /** Full-trust entry: all tools, governance layers still apply normally. */
    public static McpServerTrust trusted(String serverName, String urlPattern) {
        return new McpServerTrust(serverName, urlPattern, TrustLevel.TRUSTED, null);
    }

    /** Restricted entry: every tool call requires explicit approval. */
    public static McpServerTrust restricted(String serverName, String urlPattern,
                                            List<String> allowedTools) {
        return new McpServerTrust(serverName, urlPattern, TrustLevel.RESTRICTED, allowedTools);
    }

    /**
     * The deny entry: a server evaluated against the allowlist that matched
     * nothing. Carry it explicitly so denial is representable in audit
     * records, not just an exception message.
     */
    public static McpServerTrust untrusted(String serverName, String urlPattern) {
        return new McpServerTrust(serverName, urlPattern, TrustLevel.UNTRUSTED, null);
    }

    /** Whether a tool by this name may be called on this server at all. */
    public boolean permitsTool(String toolName) {
        return switch (level) {
            case TRUSTED -> true;
            case RESTRICTED -> allowedTools.contains(toolName);
            case UNTRUSTED -> false;
        };
    }

    /** Whether this server may be connected to. */
    public boolean isConnectable() {
        return level != TrustLevel.UNTRUSTED;
    }
}
