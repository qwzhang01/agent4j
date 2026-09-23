package io.github.qwzhang01.agent.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 *  host-side declaration of what a plugin is allowed to do.
 * <p>
 * Trust direction matters: the manifest is written by the HOST (or read
 * from a host-controlled channel), NEVER parsed out of the plugin artifact
 * itself — a hostile jar would simply lie about its own permissions. The
 * host hands the manifest to {@link PluginRegistry#load(Plugin, PluginManifest)}
 * (in-process SPI) or {@link PluginJarLoader} (external jar), and the
 * framework enforces it at the registration boundary.
 * <p>
 * Permissions are declare-to-grant: a capability not declared is denied.
 * Today only {@code tools} is enforced (the {@link PluginContext} surface
 * exposes the tool registry); {@code model} / {@code memory} / {@code
 * security} are declared now and enforced when those registry surfaces
 * exist — an honest, forward-compatible gap.
 *
 * @param name the plugin this manifest describes (must match the
 *                 plugin's {@link PluginDescriptor#name})
 * @param version expected version (informational today)
 * @param tools may register tools into the tool registry
 * @param model may contribute model adapters (declared, not yet enforced)
 * @param memory may contribute memory stores (declared, not yet enforced)
 * @param security may contribute security components (declared, not yet enforced)
 * @param sha256 expected SHA-256 of the plugin jar (hex); null = integrity
 *                 check disabled (trusted-internal artifact)
 * @param source declared origin (informational, e.g. "internal-repo"
 */
public record PluginManifest(
        String name,
        String version,
        boolean tools,
        boolean model,
        boolean memory,
        boolean security,
        String sha256,
        String source) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse a manifest from JSON, e.g.
     * {@code {"name":"search-tool","tools":true,"sha256":"..."}}.
     * Booleans default to false (declare-to-grant).
     */
    public static PluginManifest fromJson(String json) {
        final JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("plugin manifest is not valid JSON", e);
        }
        if (node == null || !node.isObject() || !node.path("name").isTextual()) {
            throw new IllegalArgumentException("plugin manifest requires a string 'name'");
        }
        return new PluginManifest(
                node.path("name").asText(),
                node.path("version").asText("0.0.0"),
                node.path("tools").asBoolean(false),
                node.path("model").asBoolean(false),
                node.path("memory").asBoolean(false),
                node.path("security").asBoolean(false),
                textOrNull(node, "sha256"),
                textOrNull(node, "source"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
