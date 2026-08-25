package io.github.qwzhang01.agent.chat;

/**
 * A speaking character in a room. Data only: the engine injects
 * {@code systemPrompt} as-is and never rewrites it.
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
}
