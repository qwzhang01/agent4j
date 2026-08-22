package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.mcp.transport.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Self-healing MCP client: a {@link McpClient} that automatically restarts a
 * crashed server subprocess and retries the failed call (Stage 10 process
 * management -- one of the 5 production gaps).
 * <p>
 * Recovery recipe, applied to {@link #listTools} and {@link #callTool}:
 * <ol>
 *   <li>Call fails with IOException</li>
 *   <li>Recoverable? Only if the transport is dead (process crashed / pipe broken).
 *       If the server is still alive (protocol-level error), reconnecting won't
 *       help -- rethrow immediately.</li>
 *   <li>Restart budget available? (max N restarts per window, min cooldown between
 *       them -- no restart storms on a fundamentally broken server)</li>
 *   <li>{@link #reconnect}: kill old transport, factory builds a fresh subprocess,
 *       redo the initialize handshake</li>
 *   <li>Retry the failed call ONCE. If it fails again, surface the error.</li>
 * </ol>
 * <p>
 * Mechanism vs strategy split: {@link McpClient#reconnect} is the mechanism
 * (how to re-establish a connection); this class is the policy (when to restart,
 * how often, and whether to retry). Same decorator spirit as Stage 9's
 * GovernedToolExecutor: existing code (McpToolAdapter, governance layers) sees
 * a plain {@link McpClient} and stays untouched.
 * <p>
 * v1 limitation: a HUNG server (process alive but unresponsive) is not detected
 * -- calls block until the transport times out. Detecting hangs needs receive
 * timeouts (Stage 18 observability territory).
 */
public class ManagedMcpClient extends McpClient {

    private static final Logger log = LoggerFactory.getLogger(ManagedMcpClient.class);

    private final McpRestartPolicy policy;
    private final ArrayDeque<Long> restartTimestamps = new ArrayDeque<>();  // guarded by this
    private volatile long lastRestartAt = -1;

    public ManagedMcpClient(McpServerDescriptor descriptor) {
        this(descriptor, McpRestartPolicy.defaults());
    }

    public ManagedMcpClient(McpServerDescriptor descriptor, McpRestartPolicy policy) {
        super(descriptor);
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    /**
     * @param transportFactory builds a FRESH transport per (re)connection
     *                         (e.g. {@code () -> new StdioTransport(command)})
     */
    public ManagedMcpClient(McpServerDescriptor descriptor,
                            Supplier<McpTransport> transportFactory,
                            McpRestartPolicy policy) {
        super(descriptor, transportFactory);
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    // ============ Self-healing operations ============

    @Override
    public List<McpToolSchema> listTools() throws IOException {
        try {
            return super.listTools();
        } catch (IOException e) {
            return recoverAndRetry("listTools", e, super::listTools);
        }
    }

    @Override
    public String callTool(String toolName, JsonNode args) throws IOException {
        try {
            return super.callTool(toolName, args);
        } catch (IOException e) {
            return recoverAndRetry("tools/call '" + toolName + "'", e,
                    () -> super.callTool(toolName, args));
        }
    }

    // ============ Health ============

    /**
     * Liveness check: connection initialized + transport open (process alive)
     * + MCP-standard ping answered.
     */
    public boolean isHealthy() {
        McpTransport t = getTransport();
        if (!isConnected() || t == null || !t.isOpen()) {
            return false;
        }
        try {
            ping();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Number of restarts consumed in the current window.
     */
    public int getRestartCount() {
        synchronized (this) {
            return restartTimestamps.size();
        }
    }

    /**
     * Wall-clock millis of the last restart, or -1 if never restarted.
     */
    public long getLastRestartAt() {
        return lastRestartAt;
    }

    // ============ Recovery internals ============

    /**
     * Core recovery path: dead-server check -> budget check -> reconnect -> single retry.
     * No recursion: the retry's own failure propagates to the caller.
     */
    private <T> T recoverAndRetry(String operation, IOException cause, IOSupplier<T> retry)
            throws IOException {
        boolean serverDead = !isTransportAlive();

        if (!serverDead || !tryAcquireRestartBudget()) {
            // Either the server is still up (protocol error -- reconnecting is useless),
            // or the budget is exhausted / cooldown is active (no restart storms).
            throw cause;
        }

        log.warn("MCP server '{}' appears dead during {} ({}), restarting (attempt {}/{})...",
                getDescriptor().name(), operation, cause.getMessage(),
                getRestartCount(), policy.maxRestarts());
        try {
            reconnect();
        } catch (IOException restartFailure) {
            throw new IOException("Restart of MCP server '" + getDescriptor().name()
                    + "' failed (gave up after " + operation + "): " + restartFailure.getMessage(),
                    restartFailure);
        }
        log.info("MCP server '{}' restarted successfully, retrying {}", getDescriptor().name(), operation);
        return retry.get();
    }

    private boolean isTransportAlive() {
        McpTransport t = getTransport();
        return t != null && t.isOpen();
    }

    /**
     * Consume one restart slot if allowed: within window budget and past cooldown.
     */
    private synchronized boolean tryAcquireRestartBudget() {
        long now = System.currentTimeMillis();

        // Drop timestamps older than the window (budget resets after quiet time)
        while (!restartTimestamps.isEmpty() && now - restartTimestamps.peekFirst() > policy.windowMs()) {
            restartTimestamps.pollFirst();
        }
        if (restartTimestamps.size() >= policy.maxRestarts()) {
            return false;
        }
        if (!restartTimestamps.isEmpty() && now - restartTimestamps.peekLast() < policy.cooldownMs()) {
            return false;
        }
        restartTimestamps.addLast(now);
        lastRestartAt = now;
        return true;
    }

    @FunctionalInterface
    private interface IOSupplier<T> {
        T get() throws IOException;
    }
}
