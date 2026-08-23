package io.github.qwzhang01.agent.product.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.security.AuditEvent;
import io.github.qwzhang01.agent.security.GovernedToolExecutor;
import io.github.qwzhang01.agent.security.InMemoryAuditLogger;
import io.github.qwzhang01.agent.security.PermissionChecker;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import io.github.qwzhang01.agent.product.definition.HttpApiDecl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.3 acceptance: a config-declared HTTP tool dropped into a ToolRegistry is
 * governed for free (D3) - same transparency McpToolAdapter proved in Stage 10.
 * A DENY policy must block the call BEFORE the HTTP request fires and must
 * leave an audit trail.
 */
class HttpApiToolGovernanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private boolean serverWasHit;

    @BeforeEach
    void startServer() throws IOException {
        serverWasHit = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/weather", exchange -> {
            serverWasHit = true;
            byte[] bytes = "{\"temp\":26}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpApiDecl weatherDecl() {
        return new HttpApiDecl(
                "weather-query", "查询城市实时天气",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/weather", "GET",
                Map.of("city", new HttpApiDecl.ParamDecl("query", "string", true, "城市")),
                new HttpApiDecl.ResponseDecl("$.temp"), null, 5);
    }

    @Test
    void denyBlocksBeforeTheHttpCallAndAudits() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new HttpApiToolFactory().create(weatherDecl()));

        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("weather-query", ToolPermission.DENY);
        InMemoryAuditLogger audit = new InMemoryAuditLogger();

        GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(policy))
                .auditLogger(audit)
                .build();

        String result = executor.execute(
                ToolCall.of("call-1", "weather-query", JSON.readTree("{\"city\":\"上海\"}")));

        // Blocked - and the block happened before the HTTP request fired.
        assertFalse(serverWasHit, "a DENIED tool must never reach the endpoint");
        assertTrue(result.contains("denied") || result.contains("DENIED") || result.contains("deny"),
                "the model should see the denial, got: " + result);

        // The audit trail records the denial.
        assertEquals(1, audit.getAll().size());
        AuditEvent event = audit.getAll().get(0);
        assertEquals(AuditEvent.AuditStatus.DENIED, event.status());
        assertEquals("weather-query", event.toolName());
    }

    @Test
    void autoPassesThroughAndAuditsExecution() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new HttpApiToolFactory().create(weatherDecl()));

        InMemoryAuditLogger audit = new InMemoryAuditLogger();
        GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(new ToolPolicy(ToolPermission.AUTO)))
                .auditLogger(audit)
                .build();

        String result = executor.execute(
                ToolCall.of("call-2", "weather-query", JSON.readTree("{\"city\":\"上海\"}")));

        assertEquals("26", result);
        assertTrue(serverWasHit, "AUTO must reach the endpoint");
        assertEquals(AuditEvent.AuditStatus.EXECUTED, audit.getAll().get(0).status());
    }
}
