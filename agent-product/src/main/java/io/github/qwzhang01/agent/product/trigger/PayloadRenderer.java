package io.github.qwzhang01.agent.product.trigger;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a message template against a JSON payload (Stage 13 M13.5).
 * <p>
 * Placeholders look like {@code {$.alert.title}} - a dot path into the
 * payload. Unknown paths render as the literal placeholder (the consumer -
 * model or human - sees that the data was missing, loud not silent).
 * Shared by webhook payload templates and ambient message templates.
 */
public final class PayloadRenderer {

    /** Placeholder: {$.a.b} - dot path into the payload JSON. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\$(\\.[^}]+)}");

    /**
     * @param template template with {@code {$.path}} placeholders, null = no template
     * @param payload  the JSON payload the paths resolve against
     * @return rendered text, or the raw payload JSON when there is no template
     */
    public static String render(String template, JsonNode payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (template == null || template.isBlank()) {
            return payload.toString();
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            String value = extract(payload, path);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String extract(JsonNode payload, String dotPath) {
        JsonNode node = payload;
        for (String segment : dotPath.split("\\.")) {
            if (segment.isEmpty()) {
                continue;
            }
            node = node.path(segment);
        }
        if (node.isMissingNode()) {
            return "{$" + dotPath + "}";  // loud miss, not silent blank
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
