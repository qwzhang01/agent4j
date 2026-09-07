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
 * @param version      opaque version tag forwarded from {@link PersonaSpec#version()};
 *                     null when the spec does not carry a version.
 *                     Surfaced in {@link io.github.qwzhang01.agent.core.agent.AgentEvent.TurnTrace}
 *                     so that replies can be correlated to a specific persona revision.
 */
public record ChatPersona(String personaId, String displayName, String systemPrompt,
                           String greeting, String version) {

    public ChatPersona {
        if (personaId == null || personaId.isBlank()) {
            throw new IllegalArgumentException("personaId must not be null or blank");
        }
        displayName = (displayName == null || displayName.isBlank()) ? personaId : displayName;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        // version and greeting are nullable — no normalization needed
    }

    /**
     * Backward-compatible constructor for code that does not need a persona version.
     * Delegates to the canonical with {@code version = null}.
     */
    public ChatPersona(String personaId, String displayName, String systemPrompt, String greeting) {
        this(personaId, displayName, systemPrompt, greeting, null);
    }

    /** Factory without version ({@code version = null}). */
    public static ChatPersona of(String personaId, String systemPrompt) {
        return new ChatPersona(personaId, personaId, systemPrompt, null, null);
    }

    /**
     * Build a persona from structured attributes.
     * {@code renderer == null} keeps {@link PersonaSpec#promptOrEmpty()} as-is.
     * {@link PersonaSpec#version()} is propagated into {@link #version()}.
     */
    public static ChatPersona render(PersonaSpec spec, PersonaRenderer renderer) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        String prompt = renderer == null ? spec.promptOrEmpty() : nullToEmpty(renderer.render(spec));
        return new ChatPersona(spec.personaId(), spec.displayName(), prompt,
                spec.attribute(PersonaSpec.GREETING), spec.version());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
