package io.github.qwzhang01.agent.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured persona input for {@link PersonaRenderer}.
 * <p>
 * Attribute keys are free-form. When no renderer is supplied,
 * {@link ChatPersona#render} reads the optional {@code systemPrompt} attribute
 * as already-rendered text — that is a fallback, not a product word list.
 *
 * @param personaId    stable id (required)
 * @param displayName  shown name (blank defaults to personaId)
 * @param attributes   product-owned fields; null values dropped
 */
public record PersonaSpec(String personaId, String displayName, Map<String, String> attributes) {

    public static final String SYSTEM_PROMPT = "systemPrompt";
    public static final String GREETING = "greeting";

    public PersonaSpec {
        if (personaId == null || personaId.isBlank()) {
            throw new IllegalArgumentException("personaId must not be null or blank");
        }
        displayName = (displayName == null || displayName.isBlank()) ? personaId : displayName;
        attributes = copyAttributes(attributes);
    }

    public static PersonaSpec of(String personaId, String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return new PersonaSpec(personaId, personaId, Map.of());
        }
        return new PersonaSpec(personaId, personaId, Map.of(SYSTEM_PROMPT, systemPrompt));
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    /**
     * Pre-rendered / raw template text. Empty when the attribute is absent.
     */
    public String promptOrEmpty() {
        String prompt = attribute(SYSTEM_PROMPT);
        return prompt == null ? "" : prompt;
    }

    private static Map<String, String> copyAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }
}
