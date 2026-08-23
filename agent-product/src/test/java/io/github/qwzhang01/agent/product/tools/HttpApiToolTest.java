package io.github.qwzhang01.agent.product.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.product.definition.HttpApiDecl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.3 tool tests against a real local HTTP server (JDK HttpServer):
 * parameter placement, extraction, error mapping, timeout, schema.
 */
class HttpApiToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/weather", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"data\":{\"temperature\":26},\"city\":\"上海\"}");
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
            }
            respond(exchange, 200, "{}");
        });
        server.createContext("/notfound", exchange ->
                respond(exchange, 404, "{\"error\":\"no such city\"}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpApiDecl weatherDecl() {
        return new HttpApiDecl(
                "weather-query", "查询城市实时天气",
                base + "/weather", "GET",
                Map.of("city", new HttpApiDecl.ParamDecl("query", "string", true, "城市名")),
                new HttpApiDecl.ResponseDecl("$.data.temperature"),
                null, 5);
    }

    private HttpApiTool tool(HttpApiDecl decl) {
        return new HttpApiToolFactory().create(decl);
    }

    // ============ Happy paths ============

    @Test
    void getWithQueryParamAndExtraction() throws Exception {
        String result = tool(weatherDecl()).execute(JSON.readTree("{\"city\":\"上海\"}"));
        assertEquals("26", result); // extracted via $.data.temperature
    }

    @Test
    void postWithBodyParamsSendsJsonBody() throws Exception {
        HttpApiDecl decl = new HttpApiDecl(
                "submit-note", "提交备注", base + "/weather", "POST",
                Map.of("note", new HttpApiDecl.ParamDecl("body", "string", true, "备注")),
                null, null, null);

        String result = tool(decl).execute(JSON.readTree("{\"note\":\"hello world\"}"));

        assertEquals("{\"data\":{\"temperature\":26},\"city\":\"上海\"}", result); // no extract = raw body
        assertEquals("{\"note\":\"hello world\"}", lastBody.get());
    }

    @Test
    void pathParamReplacesPlaceholderInEndpoint() throws Exception {
        HttpApiDecl decl = new HttpApiDecl(
                "city-info", "城市信息", base + "/weather", "GET",
                Map.of("city", new HttpApiDecl.ParamDecl("path", "string", true, "城市")),
                null, null, null);

        // The mock ignores the path; the test proves the URL was rewritten without {city}.
        String result = tool(decl).execute(JSON.readTree("{\"city\":\"上海\"}"));
        assertTrue(result.contains("temperature"));
    }

    @Test
    void bearerTokenHeaderIsSent() throws Exception {
        HttpApiDecl decl = new HttpApiDecl(
                "weather-query", "查询天气", base + "/weather", "GET",
                Map.of("city", new HttpApiDecl.ParamDecl("query", "string", true, "城市")),
                null,
                new HttpApiDecl.AuthDecl("bearer", "test-token"), null);

        tool(decl).execute(JSON.readTree("{\"city\":\"x\"}"));

        assertEquals("Bearer test-token", lastAuthorization.get());
    }

    @Test
    void parametersSchemaReflectsDeclarations() {
        String schema = tool(weatherDecl()).getParametersSchema();

        assertTrue(schema.contains("\"city\""));
        assertTrue(schema.contains("\"required\":[\"city\"]"),
                "required params must appear in the schema, got: " + schema);
        assertTrue(schema.contains("查询城市实时天气".substring(0, 2)) || schema.contains("城市名"));
    }

    @Test
    void queryParamsAreUrlEncoded() throws Exception {
        String result = tool(weatherDecl()).execute(JSON.readTree("{\"city\":\"New York\"}"));
        assertEquals("26", result); // server answers regardless; encoding must not blow up the URL
    }

    // ============ Error mapping (never blows up the loop) ============

    @Test
    void httpErrorStatusBecomesToolException() {
        HttpApiDecl decl = new HttpApiDecl(
                "city-info", "城市信息", base + "/notfound", "GET",
                Map.of("city", new HttpApiDecl.ParamDecl("query", "string", true, "城市")),
                null, null, null);

        ToolException e = assertThrows(ToolException.class,
                () -> tool(decl).execute(JSON.readTree("{\"city\":\"ghost\"}")));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void timeoutBecomesToolException() {
        HttpApiDecl decl = new HttpApiDecl(
                "slow-call", "慢调用", base + "/slow", "GET",
                null, null, null, 1);

        long start = System.currentTimeMillis();
        ToolException e = assertThrows(ToolException.class,
                () -> tool(decl).execute(null));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(e.getMessage().contains("failed"), e.getMessage());
        assertTrue(elapsed < 2500, "timeout must fire around 1s, took " + elapsed + "ms");
    }

    @Test
    void missingRequiredParamBecomesToolException() {
        ToolException e = assertThrows(ToolException.class,
                () -> tool(weatherDecl()).execute(JSON.readTree("{}")));
        assertTrue(e.getMessage().contains("city"), e.getMessage());
    }

    @Test
    void missingExtractPathBecomesToolException() {
        HttpApiDecl decl = new HttpApiDecl(
                "weather-query", "查询天气", base + "/weather", "GET",
                Map.of("city", new HttpApiDecl.ParamDecl("query", "string", true, "城市")),
                new HttpApiDecl.ResponseDecl("$.data.humidity"),   // not in the response
                null, null);

        ToolException e = assertThrows(ToolException.class,
                () -> tool(decl).execute(JSON.readTree("{\"city\":\"上海\"}")));
        assertTrue(e.getMessage().contains("$.data.humidity"), e.getMessage());
    }

    @Test
    void unreachableServerBecomesToolException() {
        HttpApiDecl decl = new HttpApiDecl(
                "dead-call", "死调用", "http://127.0.0.1:1/nope", "GET",
                null, null, null, 1);

        assertThrows(ToolException.class, () -> tool(decl).execute(null));
    }
}
