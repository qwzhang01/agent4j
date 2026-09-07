package io.github.qwzhang01.agent.chat.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured persona input for {@link io.github.qwzhang01.agent.chat.persona.PersonaRenderer}.
 * <p>
 * Attribute keys are free-form. When no renderer is supplied,
 * {@link ChatPersona#render} reads the optional {@code systemPrompt} attribute
 * as already-rendered text — that is a fallback, not a product word list.
 *
 * @param personaId    stable id (required)
 * @param displayName  shown name (blank defaults to personaId)
 * @param version      opaque version tag for the persona definition; null when unset.
 *                     Populated by the host (e.g. "v2.1.0" or an ISO timestamp) and
 *                     forwarded to {@link io.github.qwzhang01.agent.core.agent.AgentEvent.TurnTrace}
 *                     so that replies can be correlated to a specific persona revision.
 * @param attributes   product-owned fields; null values dropped
 */
public record PersonaSpec(String personaId, String displayName, String version, Map<String, String> attributes) {

    public static final String SYSTEM_PROMPT = "systemPrompt";
    public static final String GREETING = "greeting";

    public PersonaSpec {
        if (personaId == null || personaId.isBlank()) {
            throw new IllegalArgumentException("personaId must not be null or blank");
        }
        displayName = (displayName == null || displayName.isBlank()) ? personaId : displayName;
        // version is nullable — no normalization needed
        attributes = copyAttributes(attributes);
    }

    /** Convenience constructor for tests and simple cases: {@code version = null}. */
    public PersonaSpec(String personaId, String displayName, Map<String, String> attributes) {
        this(personaId, displayName, null, attributes);
    }

    /**
     * Factory without version ({@code version = null}).
     * Backward-compatible entry point for all callers that do not track persona versions.
     */
    public static PersonaSpec of(String personaId, String systemPrompt) {
        return of(personaId, null, systemPrompt);
    }

    /**
     * Factory with an explicit version tag.
     *
     * @param version opaque version string (e.g. "v2.1.0", ISO timestamp); null is allowed
     */
    public static PersonaSpec of(String personaId, String version, String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return new PersonaSpec(personaId, personaId, version, Map.of());
        }
        return new PersonaSpec(personaId, personaId, version, Map.of(SYSTEM_PROMPT, systemPrompt));
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
