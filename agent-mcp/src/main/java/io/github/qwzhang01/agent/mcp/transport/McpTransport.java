package io.github.qwzhang01.agent.mcp.transport;

import java.io.IOException;

/**
 * Transport layer abstraction for MCP (Stage 10 D2).
 * <p>
 * Carries JSON-RPC messages between client and server.
 * v1 implementation: {@link StdioTransport} (local subprocess via stdin/stdout).
 * v2 candidate: SseTransport (remote HTTP/SSE server).
 *
 * <p>The contract:
 * <ul>
 *   <li>{@link #send} writes one JSON string to the server
 *   <li>{@link #receive} blocks until one complete JSON string arrives from the server
 *   <li>Messages are newline-delimited (one JSON per line)
 *   <li>{@link #close} shuts down the transport (kills subprocess / closes socket)
 * </ul>
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Open the transport (start subprocess / connect socket).
     */
    void open() throws IOException;

    /**
     * Send one JSON-RPC message.
     *
     * @param json the serialized JSON string
     */
    void send(String json) throws IOException;

    /**
     * Block until one complete JSON-RPC message is received.
     *
     * @return the received JSON string (newline stripped)
     * @throws IOException if the transport is closed or the server dies
     */
    String receive() throws IOException;

    /**
     * Check whether the transport is still open.
     */
    boolean isOpen();

    /**
     * Close the transport (kills subprocess / closes socket).
     */
    @Override
    void close() throws IOException;
}
