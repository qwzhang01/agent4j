package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.StringJoiner;

/**
 * The server's declared capabilities, parsed from the initialize handshake
 * result (harness batch 3: capability negotiation).
 * <p>
 * Typed record behind {@link McpClient#serverCapabilities()}: the assembly
 * can now SEE what a server declared (tools/resources/prompts/logging)
 * instead of silently ignoring the declaration, and
 * {@link McpClient#supportsTools()} can guard the tool path against servers
 * that scoped themselves to non-tool capabilities.
 * <p>
 * Parsing semantics (2024-11-05 optional-field era, the one this framework
 * serves):
 * <ul>
 *   <li>result absent, or capabilities absent/null/non-object -- returns
 *       {@code null}: the server declared NOTHING. Old-era servers did not
 *       send the field at all and were still tools-only servers, so
 *       {@code null} must not mean "refuse" at the tool path.</li>
 *   <li>capabilities present but EMPTY object -- also {@code null}: it
 *       declares nothing, excludes nothing.</li>
 *   <li>capabilities object with entries -- the record: all four flags are
 *       declared by FIELD PRESENCE (the 2024-11-05 spec types each
 *       capability as an object, e.g. {@code {"tools": {"listChanged":
 *       true}}}; a shorthand boolean {@code true} is also tolerated).</li>
 * </ul>
 * The asymmetry callers rely on: a real declaration that omits tools is a
 * server that scoped itself away from tools -- the tool path refuses it
 * with a clear message; a non-declaration (null) is honored as the
 * tools-only era the framework has always served.
 *
 * @param tools     server declared the tools capability (field present)
 * @param resources server declared the resources capability
 * @param prompts   server declared the prompts capability
 * @param logging   server declared the logging capability (field present)
 */
public record McpServerCapabilities(
        boolean tools,
        boolean resources,
        boolean prompts,
        boolean logging) {

    /**
     * Parse the capabilities out of an initialize RESULT object (the
     * JSON-RPC result of the initialize request, i.e. the node carrying
     * serverInfo/capabilities -- NOT the capabilities object itself).
     * <p>
     * Tolerant by design: every "no declaration" shape (absent result,
     * absent capabilities, non-object capabilities, empty object) returns
     * null rather than failing the handshake -- a server that declares
     * nothing is still a valid old-era server.
     *
     * @param initializeResult the initialize response's result node, may be null
     * @return the parsed declaration, or null when the server declared nothing
     */
    public static McpServerCapabilities from(JsonNode initializeResult) {
        if (initializeResult == null || !initializeResult.isObject()) {
            return null;
        }
        JsonNode caps = initializeResult.get("capabilities");
        if (caps == null || !caps.isObject() || caps.isEmpty()) {
            return null;
        }
        // All four capabilities are declared by FIELD PRESENCE (2024-11-05
        // types them as capability objects, e.g. {"logging": {}}); a plain
        // boolean true is tolerated for servers that shorthand it.
        JsonNode logging = caps.get("logging");
        return new McpServerCapabilities(
                caps.has("tools"),
                caps.has("resources"),
                caps.has("prompts"),
                logging != null && (logging.isBoolean() ? logging.asBoolean(false) : true));
    }

    /**
     * Compact log-friendly form (used verbatim in McpClient's connect log).
     */
    @Override
    public String toString() {
        return new StringJoiner(",", "McpServerCapabilities[", "]")
                .add("tools=" + tools)
                .add("resources=" + resources)
                .add("prompts=" + prompts)
                .add("logging=" + logging)
                .toString();
    }
}
