package io.github.qwzhang01.agent.product.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.product.definition.HttpApiDecl;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * An ordinary {@link Tool} backed by a REST endpoint declared in YAML
 * (Stage 13 M13.3, D3).
 * <p>
 * It is deliberately NOT special: registering it in a {@code ToolRegistry}
 * makes Stage 9 governance (permissions / approval / audit / sanitization)
 * wrap it for free - the same transparency McpToolAdapter proved in Stage 10.
 * <p>
 * Error contract: every failure (missing required param, HTTP error status,
 * timeout, IO) surfaces as {@link ToolException}, which the default executor
 * turns into an {@code [ERROR] ...} message the MODEL can read and recover
 * from - the tool never blows up the AgentLoop.
 * <p>
 * Uses the JDK {@link HttpClient} (zero new dependencies, same choice as the
 * OpenAI model adapter).
 */
public final class HttpApiTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpApiDecl decl;
    private final String resolvedToken;
    private final HttpClient httpClient;

    /**
     * Prefer {@link HttpApiToolFactory#create} (it resolves ${env:} secrets).
     *
     * @param decl          the tool declaration
     * @param resolvedToken bearer token after env resolution, null = anonymous
     */
    HttpApiTool(HttpApiDecl decl, String resolvedToken) {
        this.decl = decl;
        this.resolvedToken = resolvedToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                .build();
    }

    // ============ Tool surface ============

    @Override
    public String getName() {
        return decl.name();
    }

    @Override
    public String getDescription() {
        return decl.description();
    }

    /**
     * JSON Schema generated from the declared params - what the model sees
     * when deciding how to call the tool.
     */
    @Override
    public String getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        List<String> required = new ArrayList<>();
        decl.params().forEach((name, param) -> {
            ObjectNode prop = properties.putObject(name);
            prop.put("type", param.type() == null ? "string" : param.type());
            if (param.description() != null) {
                prop.put("description", param.description());
            }
            if (param.required()) {
                required.add(name);
            }
        });
        if (!required.isEmpty()) {
            var requiredArray = schema.putArray("required");
            required.forEach(requiredArray::add);
        }
        return schema.toString();
    }

    // ============ Execution ============

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        try {
            return doExecute(arguments);
        } catch (ToolException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolException("HTTP call to " + decl.name() + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("HTTP call to " + decl.name() + " was interrupted");
        }
    }

    private String doExecute(JsonNode arguments) throws IOException, InterruptedException {
        validateRequiredParams(arguments);

        // ---- 1. Split arguments by declared placement ----
        StringBuilder query = new StringBuilder();
        ObjectNode body = MAPPER.createObjectNode();
        String url = decl.endpoint();

        if (arguments != null && arguments.isObject()) {
            for (var entry : decl.params().entrySet()) {
                String name = entry.getKey();
                HttpApiDecl.ParamDecl param = entry.getValue();
                JsonNode value = arguments.get(name);
                if (value == null || value.isNull()) {
                    continue;
                }
                switch (param.in() == null ? "query" : param.in()) {
                    case "path" -> url = url.replace("{" + name + "}", urlEncode(value.asText()));
                    case "body" -> body.set(name, value);
                    default -> {
                        if (query.length() > 0) {
                            query.append('&');
                        }
                        query.append(urlEncode(name)).append('=').append(urlEncode(value.asText()));
                    }
                }
            }
        }

        if (query.length() > 0) {
            url += (url.contains("?") ? "&" : "?") + query;
        }

        // ---- 2. Build and send the request ----
        String method = decl.method() == null ? "GET" : decl.method();
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds()));

        boolean hasBody = !body.isEmpty();
        if (hasBody) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (resolvedToken != null) {
            request.header("Authorization", "Bearer " + resolvedToken);
        }

        HttpResponse<String> response =
                httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());

        // ---- 3. Map the outcome ----
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ToolException("HTTP " + response.statusCode() + " from " + decl.name()
                    + ": " + truncate(response.body()));
        }
        return extract(response.body());
    }

    // --------------------------------------------
    // Internals
    // --------------------------------------------

    private void validateRequiredParams(JsonNode arguments) {
        for (var entry : decl.params().entrySet()) {
            if (entry.getValue().required()) {
                JsonNode value = arguments == null ? null : arguments.get(entry.getKey());
                if (value == null || value.isNull()) {
                    throw new ToolException("missing required parameter '" + entry.getKey() + "'");
                }
            }
        }
    }

    /**
     * Dot-path extraction ($.a.b.c). A missing node fails the tool so the model
     * learns the shape did not match instead of reading an empty string as data.
     */
    private String extract(String responseBody) {
        if (decl.response() == null || decl.response().extract() == null) {
            return responseBody == null ? "" : responseBody;
        }
        try {
            JsonNode node = MAPPER.readTree(responseBody == null ? "{}" : responseBody);
            for (String segment : decl.response().extract().split("\\.")) {
                if (segment.isEmpty() || segment.equals("$")) {
                    continue;
                }
                node = node.path(segment);
            }
            if (node.isMissingNode()) {
                throw new ToolException("extract path '" + decl.response().extract()
                        + "' not found in response: " + truncate(responseBody));
            }
            return node.isValueNode() ? node.asText() : node.toString();
        } catch (ToolException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolException("response is not valid JSON: " + truncate(responseBody));
        }
    }

    private int timeoutSeconds() {
        return decl.timeoutSeconds() == null ? 10 : decl.timeoutSeconds();
    }

    private static String urlEncode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
