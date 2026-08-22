package io.github.qwzhang01.agent.mcp.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
     * Block until one complete JSON-RPC message is received, or the timeout elapses.
     *
     * @throws IOException if the transport is closed, the server dies, or the wait times out
     */
    default String receive(Duration timeout) throws IOException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return receive();
        }
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcp-receive");
            t.setDaemon(true);
            return t;
        });
        try {
            return pool.submit(() -> {
                try {
                    return receive();
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("MCP receive timed out after " + timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() instanceof IOException io) {
                throw io;
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("MCP receive failed: " + (cause != null ? cause.getMessage() : e.getMessage()), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP receive interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

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
