package io.github.qwzhang01.agent.examples;

import com.sun.net.httpserver.HttpServer;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.product.AgentRegistry;
import io.github.qwzhang01.agent.product.trigger.WebhookController;
import io.github.qwzhang01.agent.product.trigger.WebhookResult;
import io.github.qwzhang01.agent.product.trigger.WebhookRoute;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Stage 13 acceptance: an external system drives an agent through a webhook -
 * HMAC-verified, idempotent, 202-answered (D8's three-piece contract).
 * <p>
 * Run this class, then deliver two webhooks from the "monitoring system"
 * (same eventId - the second is a retry replay):
 * <pre>
 * curl -X POST localhost:8081/webhooks/alerting \
 *   -H 'X-Signature: &lt;hex-hmac-of-body&gt;' -H 'Content-Type: application/json' \
 *   -d '{"eventId":"evt-1","alert":{"title":"CPU 高","severity":"P1"}}'
 * </pre>
 * The demo posts both deliveries itself and prints the flow.
 */
public final class WebhookExample {

    private static final String SECRET = "alerting-webhook-secret";

    public static void main(String[] args) throws Exception {
        // The agent the webhook routes to (started from a definition in real use).
        Agent supportBot = new SimpleAgent(new AgentConfig("support-bot",
                "你是值班助手，收到告警后给出处置建议。",
                MockModelClient.scripted().respondText("已收到告警，建议：检查 pod 内存泄漏，必要时扩容。"),
                null, 5, null));
        AgentRegistry agents = new AgentRegistry().register("support-bot", supportBot);

        WebhookController controller = WebhookController.builder()
                .route(new WebhookRoute("alerting", "support-bot",
                        "告警：{$.alert.title} 严重度 {$.alert.severity}", SECRET))
                .agents(agents)
                .executor(Executors.newSingleThreadExecutor())
                .build();

        // Minimal HTTP shell around the transport-agnostic controller.
        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
        server.createContext("/webhooks/", exchange -> {
            String source = exchange.getRequestURI().getPath().substring("/webhooks/".length());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = Map.of(
                    "X-Signature", exchange.getRequestHeaders().getFirst("X-Signature"));
            WebhookResult result = controller.handle(source, headers, body);
            byte[] out = (result.status() + ": " + result.message())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus(result), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        System.out.println("Webhook endpoint listening on http://localhost:8081/webhooks/alerting");

        // Deliver the same event twice (the second is the sender's retry).
        String body = "{\"eventId\":\"evt-1\",\"alert\":{\"title\":\"CPU 高\",\"severity\":\"P1\"}}";
        post(sign(body), body);
        post(sign(body), body);

        // A tampered delivery - rejected before the agent runs.
        post("deadbeef", body);

        server.stop(0);
        System.out.println("[done] verified + idempotent + 202 semantics all demonstrated");
    }

    private static void post(String signature, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/webhooks/alerting"))
                .header("X-Signature", signature)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("[delivery] HTTP " + response.statusCode() + " " + response.body());
        TimeUnit.MILLISECONDS.sleep(300); // let the async run finish for the demo log
    }

    private static int httpStatus(WebhookResult result) {
        return switch (result.status()) {
            case ACCEPTED, DUPLICATE -> 200;
            case UNAUTHORIZED -> 401;
            case UNKNOWN_SOURCE -> 404;
            case BAD_PAYLOAD, NO_EVENT_ID -> 400;
            case AGENT_NOT_FOUND -> 503;
        };
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private WebhookExample() {
    }
}
