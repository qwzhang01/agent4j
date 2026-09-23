package io.github.qwzhang01.agent.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 *  the allowlist itself — evaluate a descriptor against the
 * configured trust entries before any connection is attempted.
 * <p>
 * Roadmap: "远程 MCP Server allowlist 和信任等级". The gatekeeper rule:
 * <b>absence from the list is denial</b>. The default for an unevaluated
 * remote server is UNTRUSTED, and connecting is refused at configuration
 * time — an operator must have written the server into the allowlist for
 * it to be reachable at all.
 * <p>
 * Matching is by exact server name OR URL prefix. The honest scope: no
 * wildcards, no TLS pinning, no signature verification of server
 * identities (that is credential-boundary territory, tracked as a
 * gap in notes/harness-gap).
 */
public final class McpAllowlist {

    private final List<McpServerTrust> entries;

    public McpAllowlist(Collection<McpServerTrust> entries) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** Empty allowlist: every remote server is untrusted. */
    public static McpAllowlist denyAll() {
        return new McpAllowlist(List.of());
    }

    /**
     * Evaluate a server descriptor. Never throws for unknown servers —
     * denial is a return value (auditable), not an exception.
     */
    public McpServerTrust evaluate(McpServerDescriptor descriptor) {
        for (McpServerTrust entry : entries) {
            if (matches(entry, descriptor)) {
                return entry;
            }
        }
        return McpServerTrust.untrusted(descriptor.name(), descriptor.url());
    }

    /** Whether a descriptor is allowed to connect (TRUSTED or RESTRICTED). */
    public boolean isConnectable(McpServerDescriptor descriptor) {
        return evaluate(descriptor).isConnectable();
    }

    private static boolean matches(McpServerTrust entry, McpServerDescriptor descriptor) {
        if (entry.serverName().equals(descriptor.name())) {
            return true;
        }
        String pattern = entry.urlPattern();
        String url = descriptor.url();
        return pattern != null && url != null && url.startsWith(pattern);
    }

    /** Builder for readable allowlist configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder. */
    public static final class Builder {
        private final List<McpServerTrust> entries = new ArrayList<>();

        public Builder trust(String serverName, String urlPattern) {
            entries.add(McpServerTrust.trusted(serverName, urlPattern));
            return this;
        }

        public Builder restrict(String serverName, String urlPattern, String... tools) {
            entries.add(McpServerTrust.restricted(serverName, urlPattern, List.of(tools)));
            return this;
        }

        public McpAllowlist build() {
            return new McpAllowlist(entries);
        }
    }
}
