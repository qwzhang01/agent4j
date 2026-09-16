package io.github.qwzhang01.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6.2: allowlist + trust levels. The gatekeeper rule under test:
 * absence from the list is denial; RESTRICTED gates per-tool; UNTRUSTED
 * refuses to connect at all.
 */
class McpAllowlistTest {

    @Test
    void emptyList_everythingDenied() {
        McpAllowlist list = McpAllowlist.denyAll();
        McpServerDescriptor remote = McpServerDescriptor.sse("any", "https://any.example/sse");
        assertFalse(list.isConnectable(remote));
        assertEquals(McpServerTrust.TrustLevel.UNTRUSTED, list.evaluate(remote).level());
    }

    @Test
    void trustedByName_connectable() {
        McpAllowlist list = McpAllowlist.builder()
                .trust("weather", "https://weather.example")
                .build();
        McpServerDescriptor remote = McpServerDescriptor.sse("weather",
                "https://weather.example/sse");
        assertTrue(list.isConnectable(remote));
        assertEquals(McpServerTrust.TrustLevel.TRUSTED, list.evaluate(remote).level());
    }

    @Test
    void trustedByUrlPrefix_connectable() {
        McpAllowlist list = McpAllowlist.builder()
                .trust("anything", "https://internal.example")
                .build();
        // Name doesn't match, but URL prefix does
        McpServerDescriptor remote = McpServerDescriptor.sse("mystery",
                "https://internal.example/mcp/sse");
        assertTrue(list.isConnectable(remote));
    }

    @Test
    void restricted_toolGating() {
        McpAllowlist list = McpAllowlist.builder()
                .restrict("search", "https://search.example", "web_search", "read_file")
                .build();
        McpServerTrust trust = list.evaluate(
                McpServerDescriptor.sse("search", "https://search.example/sse"));

        assertEquals(McpServerTrust.TrustLevel.RESTRICTED, trust.level());
        assertTrue(trust.permitsTool("web_search"));
        assertTrue(trust.permitsTool("read_file"));
        assertFalse(trust.permitsTool("delete_everything"),
                "RESTRICTED must gate per-tool — unlisted tools denied");
    }

    @Test
    void untrusted_noToolsPermitted() {
        McpServerTrust trust = McpServerTrust.untrusted("risky", "https://risky.example");
        assertFalse(trust.isConnectable());
        assertFalse(trust.permitsTool("anything"));
    }

    @Test
    void trusted_allToolsPermitted() {
        McpServerTrust trust = McpServerTrust.trusted("good", "https://good.example");
        assertTrue(trust.isConnectable());
        assertTrue(trust.permitsTool("any_tool"));
    }

    @Test
    void deniedServer_isAuditableNotThrown() {
        // Denial is a return value (it goes into audit records), never an
        // exception from evaluate().
        McpAllowlist list = McpAllowlist.denyAll();
        McpServerTrust trust = list.evaluate(
                McpServerDescriptor.sse("unknown", "https://unknown.example/sse"));
        assertEquals("unknown", trust.serverName());
        assertEquals("https://unknown.example/sse", trust.urlPattern());
        assertEquals(List.of(), trust.allowedTools());
    }
}
