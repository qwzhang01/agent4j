package io.github.qwzhang01.agent.mcp.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * stdio transport: communicates with a local MCP server subprocess (Stage 10 D2).
 * <p>
 * Spawns a child process via {@link ProcessBuilder}, writes JSON-RPC messages to its stdin,
 * reads responses from its stdout. Messages are newline-delimited (one JSON object per line).
 * <p>
 * This is the most common MCP deployment shape (Claude Desktop, Cursor, and most reference
 * MCP servers use stdio). For remote servers, implement {@link McpTransport} with SSE/HTTP.
 */
public class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    private final List<String> command;
    private Process process;
    private OutputStream stdin;
    private BufferedReader stdout;
    private Thread stderrDrainer;

    /**
     * @param command the subprocess command (e.g. ["python", "weather_server.py"])
     */
    public StdioTransport(List<String> command) {
        this.command = Objects.requireNonNull(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
    }

    /**
     * Convenience constructor for a single-token command (e.g. ["echo"]).
     */
    public StdioTransport(String... command) {
        this(List.of(command));
    }

    @Override
    public void open() throws IOException {
        log.info("Starting MCP subprocess: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectErrorStream(false);  // keep stderr separate for debugging
        process = pb.start();
        stdin = process.getOutputStream();
        stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        startStderrDrainer();
        log.info("MCP subprocess started (pid={})", process.pid());
    }

    @Override
    public void send(String json) throws IOException {
        if (!isOpen()) {
            throw new IOException("Transport is not open");
        }
        // MCP over stdio: one JSON object per line
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        log.debug("Sent: {}", json);
    }

    @Override
    public String receive() throws IOException {
        if (!isOpen()) {
            throw new IOException("Transport is not open");
        }
        String line = stdout.readLine();
        if (line == null) {
            // EOF = subprocess exited
            throw new IOException("MCP subprocess closed its stdout (likely exited)");
        }
        log.debug("Received: {}", line);
        return line;
    }

    @Override
    public boolean isOpen() {
        return process != null && process.isAlive();
    }

    /**
     * Forcibly kill the subprocess immediately (no graceful stdin close).
     * <p>
     * Use cases: crash simulation in tests/demos, and killing a runaway server
     * that ignores graceful shutdown.
     */
    public void destroyForcibly() {
        if (process != null && process.isAlive()) {
            log.warn("Force-killing MCP subprocess (pid={})", process.pid());
            process.destroyForcibly();
        }
    }

    /**
     * Continuously drain the subprocess stderr on a daemon thread.
     * <p>
     * If nobody reads stderr, the OS pipe buffer (~64KB on macOS/Linux) fills up
     * once the server logs enough (npx download progress, server startup logs),
     * and the subprocess BLOCKS forever on its next stderr write -- the classic
     * "process management" production trap (Stage 10, one of the 5 production gaps).
     */
    private void startStderrDrainer() {
        stderrDrainer = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.info("[{}:stderr] {}", command.get(0), line);
                }
            } catch (IOException ignored) {
                // error stream closed when the process dies -- normal shutdown path
            }
        }, "mcp-stderr-" + command.get(0));
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();
    }

    @Override
    public void close() throws IOException {
        if (stdin != null) {
            try { stdin.close(); } catch (IOException ignored) { }
        }
        if (stdout != null) {
            try { stdout.close(); } catch (IOException ignored) { }
        }
        if (process != null && process.isAlive()) {
            log.info("Destroying MCP subprocess (pid={})", process.pid());
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("MCP subprocess did not exit gracefully, killing");
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
        stdout = null;
    }
}
