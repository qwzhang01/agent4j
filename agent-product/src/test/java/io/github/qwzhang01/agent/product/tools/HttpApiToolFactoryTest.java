package io.github.qwzhang01.agent.product.tools;

import io.github.qwzhang01.agent.product.definition.HttpApiDecl;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.3 factory tests: ${env:} secret resolution, missing-variable refusal.
 */
class HttpApiToolFactoryTest {

    private static HttpApiDecl declWithToken(String token) {
        return new HttpApiDecl(
                "weather-query", "查询天气", "https://api.example.com/now", "GET",
                Map.of("city", new HttpApiDecl.ParamDecl("query", "string", true, "城市")),
                null, new HttpApiDecl.AuthDecl("bearer", token), null);
    }

    @Test
    void envReferenceIsResolved() {
        HttpApiToolFactory factory = new HttpApiToolFactory(name ->
                "WEATHER_TOKEN".equals(name) ? "secret-123" : null);

        HttpApiTool tool = factory.create(declWithToken("${env:WEATHER_TOKEN}"));

        assertEquals("weather-query", tool.getName());
    }

    @Test
    void missingEnvVariableRefusesToLoad() {
        HttpApiToolFactory factory = new HttpApiToolFactory(name -> null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.create(declWithToken("${env:WEATHER_TOKEN}")));
        // The message must name the variable so the operator knows what to set.
        assertTrue(e.getMessage().contains("WEATHER_TOKEN"), e.getMessage());
    }

    @Test
    void literalTokenPassesThrough() {
        HttpApiToolFactory factory = new HttpApiToolFactory(name -> {
            throw new AssertionError("literal token must not touch the environment");
        });

        // Does not throw - literal tokens are for tests/local only.
        factory.create(declWithToken("literal-token"));
    }

    @Test
    void malformedEnvReferenceIsTreatedAsLiteral() {
        // "${env:}" and "${env NAME}" do not match the strict pattern - they pass
        // through as literals (and then fail against the real API, loudly).
        HttpApiToolFactory factory = new HttpApiToolFactory(name -> null);
        factory.create(declWithToken("${env:}"));
    }

    @Test
    void nullTokenIsRejected() {
        HttpApiToolFactory factory = new HttpApiToolFactory(name -> "x");
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(declWithToken(null)));
    }
}
