package io.github.qwzhang01.agent.mcp.transport;

import io.github.qwzhang01.agent.mcp.McpServerDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Stage 6.2: HTTP/SSE transport adapter for remote MCP servers.
 * <p>
 * The stdio transport talks to a local subprocess — fine for dev, wrong for
 * production: real deployments front MCP servers over HTTP. The older SSE
 * dialect (protocol rev "2024-11-05") works like this:
 * <ol>
 *   <li>Client GETs the SSE endpoint; the server answers with an endpoint
 *       event (the channel for server-to-client messages)</li>
 *   <li>Client POSTs JSON-RPC requests to that endpoint (client-to-server)</li>
 *   <li>Server answers each request as an SSE {@code data:} event on the
 *       GET channel, correlated by JSON-RPC id</li>
 * </ol>
 * <p>
 * Concurrency design (the part that bites): the drain thread reads the RAW
 * response InputStream and splits SSE lines itself — never wrapped in a
 * BufferedReader/InputStreamReader. A blocking {@code readLine()} on a
 * wrapped reader holds the wrapper's monitor while parked on the socket,
 * and {@code close()} from another thread then deadlocks on that monitor.
 * Reading the raw stream keeps {@code close()} lock-free: it closes the
 * underlying stream (the HTTP layer cancels the subscription and the
 * blocked read wakes with EOF/error), and if that ever lags the drain
 * thread is a daemon and dies with the JVM.
 * <p>
 * The newer Streamable-HTTP dialect (single endpoint, POST returns SSE) is
 * deliberately NOT implemented — honest gap, tracked in the roadmap.
 */
public class SseTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(SseTransport.class);

    private final McpServerDescriptor descriptor;
    private final HttpClient httpClient;
    private final Duration connectTimeout;
    private final Supplier<String> authTokenSupplier;

    private volatile InputStream rawStream;
    private volatile Thread readerThread;
    private volatile String messageEndpoint;   // where POSTs go
    private final BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
    private volatile boolean open = false;

    /**
     * @param descriptor        must be an SSE descriptor ({@code url} set)
     * @param httpClient        the HTTP layer (injectable for tests)
     * @param connectTimeout    timeout for the initial GET and the endpoint wait
     * @param authTokenSupplier supplies the auth header value for every
     *                          request (host-managed credentials); null = no auth
     */
    public SseTransport(McpServerDescriptor descriptor, HttpClient httpClient,
                        Duration connectTimeout, Supplier<String> authTokenSupplier) {
        this.descriptor = descriptor;
        this.httpClient = httpClient;
        this.connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(30);
        this.authTokenSupplier = authTokenSupplier;
        if (descriptor.url() == null || descriptor.url().isBlank()) {
            throw new IllegalArgumentException(
                    "SseTransport requires McpServerDescriptor.sse(name, url)");
        }
    }

    /** No-auth convenience constructor. */
    public SseTransport(McpServerDescriptor descriptor, HttpClient httpClient) {
        this(descriptor, httpClient, null, null);
    }

    @Override
    public synchronized void open() throws IOException {
        if (open) {
            return;
        }
        // Step 1: GET the SSE channel. The server's first event tells us
        // where to POST (endpoint event).
        HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                .uri(URI.create(descriptor.url()))
                .timeout(connectTimeout)
                .GET();
        applyAuth(getBuilder);
        HttpResponse<InputStream> resp;
        try {
            resp = httpClient.send(getBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IOException("SSE connect failed for '" + descriptor.name() + "': "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SSE connect interrupted", e);
        }
        if (resp.statusCode() != 200) {
            try {
                resp.body().close();
            } catch (IOException ignored) {
                // close best-effort
            }
            throw new IOException("SSE connect rejected with HTTP " + resp.statusCode()
                    + " for '" + descriptor.name() + "'");
        }

        rawStream = resp.body();
        open = true;

        // Step 2: background thread parses SSE frames (event/data pairs) and
        // discovers the POST endpoint from the endpoint event.
        readerThread = new Thread(this::drainSseChannel, "mcp-sse-" + descriptor.name());
        readerThread.setDaemon(true);
        readerThread.start();

        // Step 3: the handshake is not done until the server told us where
        // to POST. Without this, send() racing the reader fails on
        // "no message endpoint yet".
        long deadline = System.currentTimeMillis() + connectTimeout.toMillis();
        while (messageEndpoint == null) {
            if (!readerThread.isAlive()) {
                throw new IOException("SSE channel for '" + descriptor.name()
                        + "' died before sending the endpoint event");
            }
            if (System.currentTimeMillis() >= deadline) {
                close();
                throw new IOException("Timed out waiting for endpoint event from '"
                        + descriptor.name() + "'");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for endpoint event", e);
            }
        }
        log.debug("SSE transport open to '{}' (endpoint: {})", descriptor.url(),
                messageEndpoint);
    }

    @Override
    public void send(String json) throws IOException {
        if (!open || messageEndpoint == null) {
            throw new IOException("SSE transport not open (no message endpoint yet)");
        }
        HttpRequest.Builder postBuilder = HttpRequest.newBuilder()
                .uri(URI.create(messageEndpoint))
                .timeout(connectTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        applyAuth(postBuilder);
        try {
            HttpResponse<Void> resp = httpClient.send(postBuilder.build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                throw new IOException("MCP POST to '" + descriptor.name()
                        + "' rejected with HTTP " + resp.statusCode());
            }
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP POST interrupted", e);
        }
    }

    @Override
    public String receive() throws IOException {
        if (!open) {
            throw new IOException("SSE transport closed");
        }
        String msg = inbound.poll();
        if (msg != null) {
            return msg;
        }
        try {
            String waited = inbound.poll(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (waited == null) {
                throw new IOException("SSE receive timed out after " + connectTimeout
                        + " for '" + descriptor.name() + "'");
            }
            return waited;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for SSE message", e);
        }
    }

    @Override
    public boolean isOpen() {
        return open && readerThread != null && readerThread.isAlive();
    }

    /**
     * Lock-free close: closes the RAW stream only. Never touches a wrapped
     * reader (whose monitor may be held by a blocked read — the deadlock
     * this design exists to avoid). Cancelling the HTTP body stream makes
     * the drain thread's blocked read fail; the drain thread is a daemon
     * as a final safety net.
     */
    @Override
    public synchronized void close() throws IOException {
        open = false;
        InputStream raw = rawStream;
        if (raw != null) {
            try {
                raw.close();
            } catch (IOException e) {
                log.debug("SSE raw stream close: {}", e.getMessage());
            }
        }
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
        }
        inbound.clear();
        messageEndpoint = null;
        log.debug("SSE transport closed for '{}'", descriptor.name());
    }


    private static final String CLOSE_FRAME = "{\"jsonrpc\":\"2.0\","
            + "\"method\":\"connection/closed\",\"params\":{\"reason\":\"sse channel ended\"}}";

    /** Reads raw bytes, splits lines, assembles SSE frames. */
    void drainSseChannel() {
        byte[] buf = new byte[8192];
        StringBuilder lineBuffer = new StringBuilder();
        String currentEvent = null;
        StringBuilder dataBuffer = new StringBuilder();
        try {
            InputStream in = rawStream;
            int n;
            while (open && (n = in.read(buf)) >= 0) {
                for (int i = 0; i < n; i++) {
                    char c = (char) (buf[i] & 0xFF);
                    if (c == '\n') {
                        String line = lineBuffer.toString();
                        lineBuffer.setLength(0);
                        if (line.endsWith("\r")) {
                            line = line.substring(0, line.length() - 1);
                        }
                        if (line.isEmpty()) {
                            // Frame boundary: dispatch the accumulated event
                            if (dataBuffer.length() > 0) {
                                dispatchFrame(currentEvent, dataBuffer.toString());
                            }
                            currentEvent = null;
                            dataBuffer.setLength(0);
                        } else if (line.startsWith("event:")) {
                            currentEvent = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            dataBuffer.append(line.substring(5).trim());
                        }
                    } else {
                        lineBuffer.append(c);
                    }
                }
            }
        } catch (Exception e) {
            // read failed: stream closed (normal shutdown) or channel died
        }
        if (open) {
            log.warn("SSE channel for '{}' ended", descriptor.name());
            inbound.offer(CLOSE_FRAME);
        }
    }

    /**
     * The endpoint event carries the POST URL (path; resolved against the
     * base URL). Data events are JSON-RPC messages bound for receive().
     */
    void dispatchFrame(String event, String data) {
        if ("endpoint".equals(event)) {
            String resolved = resolveEndpoint(data.trim());
            if (resolved != null) {
                messageEndpoint = resolved;
                log.debug("MCP message endpoint for '{}': {}", descriptor.name(), resolved);
            }
            return;
        }
        // JSON-RPC message (response or notification) — queue for receive()
        inbound.offer(data);
    }

    private String resolveEndpoint(String path) {
        try {
            URI base = URI.create(descriptor.url());
            return base.resolve(path).toString();
        } catch (Exception e) {
            log.warn("Unresolvable endpoint event from '{}': {}", descriptor.name(), path);
            return null;
        }
    }

    private void applyAuth(HttpRequest.Builder builder) {
        Supplier<String> supplier = authTokenSupplier;
        if (supplier != null) {
            String token = supplier.get();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", token);
            }
        }
    }
}
