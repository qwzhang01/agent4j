package io.github.qwzhang01.agent.product.definition;

import java.util.Map;
import java.util.Objects;

/**
 * Inline HTTP API tool declaration (Stage 13 M13.3, D3: a config-declared REST
 * endpoint becomes a Tool without a single line of Java).
 * <pre>{@code
 * tools:
 *   - http:
 *       name: weather-query
 *       description: 查询城市实时天气
 *       endpoint: https://api.weather.example/v1/now
 *       method: GET                      # default GET
 *       params:
 *         city: { in: query, type: string, required: true }
 *         note: { in: body, type: string, required: false }
 *       response:
 *         extract: "$.data.temperature"  # dot path; absent = raw body
 *       auth:
 *         type: bearer
 *         token: "${env:WEATHER_TOKEN}"  # secrets NEVER literal in YAML
 *       timeoutSeconds: 3                # default 10
 * }</pre>
 * <p>
 * This is the config-layer counterpart of MCP (Stage 10): MCP connects to
 * servers that implement the protocol (discovery for free); this connects to
 * plain REST APIs the author has to describe (no server-side cooperation).
 * Once registered, both are ordinary {@code Tool}s - identical governance.
 *
 * @param name           tool name (the registry key and the model-visible name)
 * @param description    what the tool does - clarity matters, the model reads it
 * @param endpoint       URL, may contain {@code {param}} path placeholders
 * @param method         HTTP method, null = GET
 * @param params         parameter declarations keyed by name, null = no params
 * @param response       response extraction, null = raw body
 * @param auth           auth wiring, null = anonymous
 * @param timeoutSeconds request timeout, null = 10
 */
public record HttpApiDecl(
        String name,
        String description,
        String endpoint,
        String method,
        Map<String, ParamDecl> params,
        ResponseDecl response,
        AuthDecl auth,
        Integer timeoutSeconds) {

    public HttpApiDecl {
        params = params == null ? Map.of() : Map.copyOf(params);
        if (method != null) {
            method = method.toUpperCase();
        }
    }

    /**
     * A single parameter declaration.
     *
     * @param in          where the value goes: query | body | path
     * @param type        documentation for the model (string/number/boolean)
     * @param required    whether the model must supply it
     * @param description parameter hint for the model
     */
    public record ParamDecl(String in, String type, boolean required, String description) {
    }

    /**
     * Response extraction: a dot path into the JSON body ({@code $.data.temperature}).
     * Missing nodes fail the tool (the model learns the extraction did not match).
     *
     * @param extract dot path starting with {@code $.}
     */
    public record ResponseDecl(String extract) {
    }

    /**
     * Auth wiring. v1 supports bearer tokens only.
     * <p>
     * The token must be an environment reference {@code ${env:NAME}} - a literal
     * secret in YAML is accepted by the parser but rejected by production
     * discipline (the factory resolves references; literals pass through with
     * a javadoc warning here).
     *
     * @param type  "bearer" (v1)
     * @param token token value or {@code ${env:NAME}} reference
     */
    public record AuthDecl(String type, String token) {

        public AuthDecl {
            Objects.requireNonNull(type, "auth.type must not be null");
        }
    }
}
