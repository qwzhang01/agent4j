package io.github.qwzhang01.agent.rag.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.qwzhang01.agent.rag.RerankException;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpRerankerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String responseBody = "{\"results\":[]}";
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean hang;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        calls.incrementAndGet();
        requestPath.set(ex.getRequestURI().getPath());
        authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (hang) {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static ScoredChunk candidate(int i, String text) {
        Chunk chunk = new Chunk("d#" + i, "d", "Doc", "d.md", List.of("Sec"), text, 0, 0, null, null);
        return ScoredChunk.of(chunk, ScoredChunk.RRF, 1.0 / (i + 1));
    }

    private static List<ScoredChunk> candidates() {
        return List.of(candidate(0, "alpha"), candidate(1, "beta"), candidate(2, "gamma"), candidate(3, "delta"));
    }

    @Test
    void happyPathSortsUnsortedResultsMapsIndicesAndKeepsTopN() throws Exception {
        responseBody = """
                {"model":"m","results":[
                  {"index":0,"relevance_score":0.10,"document":{"text":"x"}},
                  {"index":2,"relevance_score":0.93},
                  {"index":3,"relevance_score":0.55},
                  {"index":1,"relevance_score":0.70}
                ],"usage":{"total_tokens":12}}""";
        HttpReranker reranker = HttpReranker.builder().baseUrl(baseUrl).model("bge-reranker-v2-m3").build();

        List<ScoredChunk> out = reranker.rerank("what is gamma", candidates(), 2);

        assertEquals(List.of("d#2", "d#1"), out.stream().map(c -> c.chunk().chunkId()).toList());
        assertEquals(0.93, out.get(0).score(), 1e-9);
        assertEquals(0.93, out.get(0).signals().get(ScoredChunk.RERANK), 1e-9);
        assertEquals(1.0 / 3, out.get(0).signals().get(ScoredChunk.RRF), 1e-9);
        assertEquals("/v1/rerank", requestPath.get());

        JsonNode body = MAPPER.readTree(requestBody.get());
        assertEquals("bge-reranker-v2-m3", body.get("model").asText());
        assertEquals("what is gamma", body.get("query").asText());
        assertEquals(2, body.get("top_n").asInt());
        assertEquals(4, body.get("documents").size());
        assertEquals("Doc | Sec\ngamma", body.get("documents").get(2).asText());
    }

    @Test
    void authorizationHeaderOnlyWhenApiKeySet() {
        responseBody = "{\"results\":[{\"index\":0,\"relevance_score\":0.5}]}";
        HttpReranker.builder().baseUrl(baseUrl).build().rerank("q", candidates(), 1);
        assertNull(authHeader.get());

        HttpReranker withKey = HttpReranker.builder().baseUrl(baseUrl + "/").apiKey("sk-secret").build();
        withKey.rerank("q", candidates(), 1);
        assertEquals("Bearer sk-secret", authHeader.get());
        assertFalse(withKey.toString().contains("sk-secret"));
    }

    @Test
    void customPathTruncationAndDocumentText() throws Exception {
        responseBody = "{\"results\":[{\"index\":0,\"relevance_score\":0.5}]}";
        HttpReranker reranker = HttpReranker.builder()
                .baseUrl(baseUrl).path("rerank").maxDocumentChars(3).documentText(Chunk::text).build();

        reranker.rerank("q", candidates(), 4);

        assertEquals("/rerank", requestPath.get());
        JsonNode body = MAPPER.readTree(requestBody.get());
        assertEquals("alp", body.get("documents").get(0).asText());
        assertFalse(body.has("model"));
    }

    @Test
    void emptyCandidatesSkipHttp() {
        assertEquals(List.of(), HttpReranker.builder().baseUrl(baseUrl).build().rerank("q", List.of(), 5));
        assertEquals(0, calls.get());
    }

    @Test
    void non2xxIncludesStatusAndBodySnippet() {
        status = 500;
        responseBody = "{\"error\":\"model overloaded\"}";
        RerankException e = assertThrows(RerankException.class,
                () -> HttpReranker.builder().baseUrl(baseUrl).apiKey("sk-secret").build().rerank("q", candidates(), 2));
        assertTrue(e.getMessage().contains("500"), e.getMessage());
        assertTrue(e.getMessage().contains("model overloaded"), e.getMessage());
        assertFalse(e.getMessage().contains("sk-secret"));
    }

    @Test
    void malformedJsonFails() {
        responseBody = "<html>oops</html>";
        RerankException e = assertThrows(RerankException.class,
                () -> HttpReranker.builder().baseUrl(baseUrl).build().rerank("q", candidates(), 2));
        assertTrue(e.getMessage().contains("not JSON"), e.getMessage());

        responseBody = "{\"data\":[]}";
        assertThrows(RerankException.class,
                () -> HttpReranker.builder().baseUrl(baseUrl).build().rerank("q", candidates(), 2));

        responseBody = "{\"results\":[{\"index\":\"a\",\"relevance_score\":0.1}]}";
        assertThrows(RerankException.class,
                () -> HttpReranker.builder().baseUrl(baseUrl).build().rerank("q", candidates(), 2));
    }

    @Test
    void indexOutOfRangeFails() {
        responseBody = "{\"results\":[{\"index\":4,\"relevance_score\":0.9}]}";
        RerankException e = assertThrows(RerankException.class,
                () -> HttpReranker.builder().baseUrl(baseUrl).build().rerank("q", candidates(), 2));
        assertTrue(e.getMessage().contains("out of range"), e.getMessage());
    }

    @Test
    void timeoutFails() {
        hang = true;
        long start = System.nanoTime();
        RerankException e = assertThrows(RerankException.class,
                () -> HttpReranker.builder().baseUrl(baseUrl).timeout(Duration.ofMillis(200)).build()
                        .rerank("q", candidates(), 2));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
    }

    @Test
    void connectionRefusedFails() throws IOException {
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        assertThrows(RerankException.class,
                () -> HttpReranker.builder().baseUrl("http://127.0.0.1:" + port).build().rerank("q", candidates(), 2));
    }

    @Test
    void baseUrlRequired() {
        assertThrows(NullPointerException.class, () -> HttpReranker.builder().build());
    }
}
