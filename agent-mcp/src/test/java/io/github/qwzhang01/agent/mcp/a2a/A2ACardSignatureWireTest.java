package io.github.qwzhang01.agent.mcp.a2a;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch 4 wire tests for card identity: a server with a signing key serves
 * the card signature header over the well-known route; a client with a
 * trust store accepts a properly signed card, and fail-closed refuses a
 * card without a signature, with an unknown key, or tampered bytes. The
 * unsigned legacy path (no key, no trust store) stays byte-compatible.
 *
 * <p>Loopback-only (127.0.0.1, JDK HttpServer/HttpClient, no external
 * process or network), so it runs in the default suite like the MCP
 * loopback tests: card identity is a Batch 4 hard requirement, and a
 * trust feature whose wire path is never exercised locally would be
 * coverage theater. The heavier cross-process transport ITs stay in the
 * {@code a2a-it} matrix.</p>
 */
class A2ACardSignatureWireTest {

    private HttpA2AServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final KeyPair signerKeys = A2ACardIdentity.generateKeyPair();
    private final KeyPair otherKeys = A2ACardIdentity.generateKeyPair();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static Agent noopAgent() {
        return new Agent() {
            @Override public String run(String in) { return "ok"; }
            @Override public String run(String in, AgentState s) { return "ok"; }
            @Override public AgentConfig getConfig() { return null; }
        };
    }

    private HttpA2AServer startSigned() throws Exception {
        server = new HttpA2AServer(
                new AgentCard("signed-svc", "test", List.of("x"), "u", "1.0"),
                noopAgent(), 0, null,
                new InMemoryA2ATaskStore(), null, null,
                signerKeys);
        server.start();
        return server;
    }

    private HttpA2AServer startUnsigned() throws Exception {
        server = new HttpA2AServer(
                new AgentCard("plain-svc", "test", List.of("x"), "u", "1.0"),
                noopAgent());
        server.start();
        return server;
    }

    private HttpA2AClient client(PublicKey... trusted) {
        return new HttpA2AClient("http://127.0.0.1:" + server.port(),
                java.time.Duration.ofSeconds(10), http, Set.of(trusted));
    }

    @Test
    void signedServerCarriesTheSignatureHeader() throws Exception {
        HttpA2AServer started = startSigned();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + started.port()
                                + HttpA2AServer.WELL_KNOWN_PATH))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String header = response.headers()
                .firstValue(A2ACardIdentity.CARD_SIGNATURE_HEADER).orElse(null);
        assertNotNull(header, "a signing server must serve the signature header");
        A2ACardIdentity.CardSignature parsed = A2ACardIdentity.parseHeader(header);
        assertNotNull(parsed);
        assertEquals(A2ACardIdentity.keyIdOf(signerKeys.getPublic()), parsed.keyId());
        assertTrue(A2ACardIdentity.verify(
                response.body().getBytes(StandardCharsets.UTF_8),
                parsed.signature(), signerKeys.getPublic()),
                "the served signature must verify over the served card bytes");
    }

    @Test
    void unsignedServerServesNoSignatureHeader() throws Exception {
        HttpA2AServer started = startUnsigned();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + started.port()
                                + HttpA2AServer.WELL_KNOWN_PATH))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue(A2ACardIdentity.CARD_SIGNATURE_HEADER)
                        .isEmpty(),
                "a legacy server must not invent a signature header");
    }

    @Test
    void trustedClientAcceptsTheSignedCard() throws Exception {
        startSigned();
        List<AgentCard> cards = client(signerKeys.getPublic()).discoverAgents();
        assertEquals(1, cards.size());
        assertEquals("signed-svc", cards.get(0).name());
    }

    @Test
    void trustStoreClientRefusesAnUnsignedCard() throws Exception {
        startUnsigned();
        A2AHttpException refused = assertThrows(A2AHttpException.class,
                () -> client(signerKeys.getPublic()).discoverAgents());
        assertTrue(refused.getMessage().contains("no verifiable signature"),
                "the refusal must name the missing signature, got: " + refused.getMessage());
    }

    @Test
    void trustStoreClientRefusesAnUnknownSigningKey() throws Exception {
        startSigned();
        A2AHttpException refused = assertThrows(A2AHttpException.class,
                () -> client(otherKeys.getPublic()).discoverAgents());
        assertTrue(refused.getMessage().contains("unknown key"),
                "the refusal must name the unknown key, got: " + refused.getMessage());
    }

    @Test
    void emptyTrustStoreKeepsTheLegacyDiscoveryPath() throws Exception {
        startUnsigned();
        List<AgentCard> cards = client().discoverAgents();
        assertEquals(1, cards.size(), "no trust store = D7 legacy: cards are routing input");
    }

    @Test
    void emptyTrustStoreAcceptsEvenASignedCardWithoutCaring() throws Exception {
        startSigned();
        List<AgentCard> cards = client().discoverAgents();
        assertEquals(1, cards.size(),
                "a signed card is still a valid card for a client that opted out");
    }

    @Test
    void cardSigningKeyIdExposesTheFingerprint() throws Exception {
        HttpA2AServer started = startSigned();
        assertEquals(A2ACardIdentity.keyIdOf(signerKeys.getPublic()),
                started.cardSigningKeyId());
    }

    @Test
    void tamperedSignatureDoesNotVerify() throws Exception {
        startSigned();
        // Reach past the client into the raw header semantics: a signature
        // value that does not match the served bytes must fail verification.
        HttpA2AServer started = server;
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + started.port()
                                + HttpA2AServer.WELL_KNOWN_PATH))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String header = response.headers()
                .firstValue(A2ACardIdentity.CARD_SIGNATURE_HEADER).orElse(null);
        assertNotNull(header);
        A2ACardIdentity.CardSignature parsed = A2ACardIdentity.parseHeader(header);
        assertNotNull(parsed);
        // Sign DIFFERENT bytes with the right key: verification over the
        // served body must fail (this is the tampered-card scenario).
        String wrongBodySignature = A2ACardIdentity.sign(
                A2ACardIdentity.utf8("{\"name\":\"evil-twin\"}"), signerKeys.getPrivate());
        assertFalse(A2ACardIdentity.verify(
                response.body().getBytes(StandardCharsets.UTF_8),
                wrongBodySignature, signerKeys.getPublic()),
                "a signature over other card bytes must not verify");
    }
}
