package io.github.qwzhang01.agent.rag.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.rag.RerankException;
import io.github.qwzhang01.agent.rag.Reranker;
import io.github.qwzhang01.agent.rag.internal.Texts;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link Reranker} speaking the Cohere/Jina-compatible rerank protocol over HTTP.
 * <p>
 * {@code POST {baseUrl}{path}} with body
 * {@code {"model": m, "query": q, "documents": [..], "top_n": n}} and optional
 * {@code Authorization: Bearer <apiKey>}; expects
 * {@code {"results": [{"index": i, "relevance_score": s}, ..]}} in any order.
 * Works against cloud providers and self-hosted services exposing the same shape.
 */
public final class HttpReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(HttpReranker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BODY_SNIPPET_CHARS = 300;
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int maxDocumentChars;
    private final Function<Chunk, String> documentText;
    private final HttpClient httpClient;

    private HttpReranker(Builder b) {
        String base = Objects.requireNonNull(b.baseUrl, "baseUrl");
        String path = b.path == null ? "" : b.path;
        if (base.endsWith("/") && path.startsWith("/")) {
            base = base.substring(0, base.length() - 1);
        } else if (!base.endsWith("/") && !path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        this.endpoint = URI.create(base + path);
        this.model = b.model;
        this.apiKey = b.apiKey == null || b.apiKey.isBlank() ? null : b.apiKey;
        this.timeout = Objects.requireNonNull(b.timeout, "timeout");
        if (b.maxDocumentChars <= 0) {
            throw new IllegalArgumentException("maxDocumentChars must be positive");
        }
        this.maxDocumentChars = b.maxDocumentChars;
        this.documentText = Objects.requireNonNull(b.documentText, "documentText");
        this.httpClient = b.httpClient != null ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Resolved request URI. */
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty() || topN <= 0) {
            return List.of();
        }
        int n = Math.min(topN, candidates.size());
        HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(query, candidates, n), StandardCharsets.UTF_8));
        if (apiKey != null) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = rb.build();

        long start = System.nanoTime();
        int status;
        String body;
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            body = readBody(response.body());
        } catch (HttpTimeoutException e) {
            throw new RerankException("rerank timed out after " + timeout.toMillis() + "ms: " + endpoint, e);
        } catch (IOException e) {
            throw new RerankException("rerank request failed: " + endpoint + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RerankException("rerank interrupted: " + endpoint, e);
        }
        if (status < 200 || status >= 300) {
            throw new RerankException("rerank HTTP " + status + " from " + endpoint + ": " + snippet(body));
        }
        List<ScoredChunk> results = parse(body, candidates, n);
        log.debug("rerank {} candidates -> {} in {}ms", candidates.size(), results.size(),
                (System.nanoTime() - start) / 1_000_000);
        return results;
    }

    private String requestBody(String query, List<ScoredChunk> candidates, int topN) {
        ObjectNode body = MAPPER.createObjectNode();
        if (model != null) {
            body.put("model", model);
        }
        body.put("query", query);
        ArrayNode docs = body.putArray("documents");
        for (ScoredChunk candidate : candidates) {
            String text = documentText.apply(candidate.chunk());
            docs.add(Texts.truncate(text == null ? "" : text, maxDocumentChars));
        }
        body.put("top_n", topN);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new RerankException("cannot serialize rerank request", e);
        }
    }

    private String readBody(InputStream in) throws IOException {
        try (in) {
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new RerankException("rerank response exceeds " + MAX_RESPONSE_BYTES + " bytes: " + endpoint);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private List<ScoredChunk> parse(String body, List<ScoredChunk> candidates, int topN) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new RerankException("rerank response is not JSON: " + snippet(body), e);
        }
        JsonNode results = root == null ? null : root.get("results");
        if (results == null || !results.isArray()) {
            throw new RerankException("rerank response has no 'results' array: " + snippet(body));
        }
        List<ScoredChunk> scored = new ArrayList<>(results.size());
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : results) {
            JsonNode index = item.get("index");
            JsonNode score = item.get("relevance_score");
            if (index == null || !index.canConvertToInt() || !index.isIntegralNumber()
                    || score == null || !score.isNumber()) {
                throw new RerankException("rerank result missing numeric 'index'/'relevance_score': " + snippet(item.toString()));
            }
            int i = index.intValue();
            if (i < 0 || i >= candidates.size()) {
                throw new RerankException("rerank result index " + i + " out of range [0," + candidates.size() + ")");
            }
            double value = score.doubleValue();
            if (!Double.isFinite(value)) {
                throw new RerankException("rerank result " + i + " has non-finite relevance_score: " + score);
            }
            if (seen.add(i)) {
                scored.add(candidates.get(i).withStage(ScoredChunk.RERANK, value));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.size() <= topN ? List.copyOf(scored) : List.copyOf(scored.subList(0, topN));
    }

    private static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return "<empty body>";
        }
        String head = Texts.truncate(body, BODY_SNIPPET_CHARS * 4);
        String flat = head.replaceAll("\\s+", " ").strip();
        return flat.length() <= BODY_SNIPPET_CHARS && head.length() == body.length()
                ? flat : Texts.truncate(flat, BODY_SNIPPET_CHARS) + "...";
    }

    @Override
    public String toString() {
        return "HttpReranker[" + endpoint + ", model=" + model + ", auth=" + (apiKey != null) + "]";
    }

    /** Builder for {@link HttpReranker}. */
    public static final class Builder {
        private String baseUrl;
        private String path = "/v1/rerank";
        private String model;
        private String apiKey;
        private Duration timeout = Duration.ofSeconds(10);
        private int maxDocumentChars = 2000;
        private Function<Chunk, String> documentText = Chunk::contextualText;
        private HttpClient httpClient;

        private Builder() {
        }

        /** Required, e.g. {@code https://api.jina.ai} or {@code http://localhost:8000}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Default {@code /v1/rerank}. */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /** Sent as {@code model}; omitted from the body when null. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sent as bearer token; no {@code Authorization} header when null or blank. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Per-request (and connect) timeout; default 10s. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Each document is truncated to this many chars; default 2000. */
        public Builder maxDocumentChars(int maxDocumentChars) {
            this.maxDocumentChars = maxDocumentChars;
            return this;
        }

        /** Text sent per candidate; default {@link Chunk#contextualText()}. */
        public Builder documentText(Function<Chunk, String> documentText) {
            this.documentText = documentText;
            return this;
        }

        /** Custom client (proxy, TLS); default is a new client with {@code timeout} as connect timeout. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public HttpReranker build() {
            return new HttpReranker(this);
        }
    }
}
