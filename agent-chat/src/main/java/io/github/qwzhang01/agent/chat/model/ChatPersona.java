package io.github.qwzhang01.agent.chat.model;

import io.github.qwzhang01.agent.chat.persona.PersonaRenderer;

/**
 * A speaking character in a room. Data only: the engine injects
 * {@code systemPrompt} as-is and never rewrites it.
 * Optional {@link PersonaRenderer} fills that string from {@link PersonaSpec}
 * before the persona enters the room; a null renderer keeps the spec prompt.
 *
 * @param personaId    stable id used for @mention routing
 * @param displayName  name shown to the player (blank defaults to personaId)
 * @param systemPrompt persona text sent to the model (may be blank)
 * @param greeting     optional opening line (engine does not auto-send it)
 */
public record ChatPersona(String personaId, String displayName, String systemPrompt, String greeting) {

    public ChatPersona {
        if (personaId == null || personaId.isBlank()) {
            throw new IllegalArgumentException("personaId must not be null or blank");
        }
        displayName = (displayName == null || displayName.isBlank()) ? personaId : displayName;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    public static ChatPersona of(String personaId, String systemPrompt) {
        return new ChatPersona(personaId, personaId, systemPrompt, null);
    }

    /**
     * Build a persona from structured attributes.
     * {@code renderer == null} keeps {@link PersonaSpec#promptOrEmpty()} as-is.
     */
    public static ChatPersona render(PersonaSpec spec, PersonaRenderer renderer) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        String prompt = renderer == null ? spec.promptOrEmpty() : nullToEmpty(renderer.render(spec));
        return new ChatPersona(spec.personaId(), spec.displayName(), prompt, spec.attribute(PersonaSpec.GREETING));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
