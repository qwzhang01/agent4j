package io.github.qwzhang01.agent.chat;

/**
 * Turns structured persona attributes into system text.
 * <p>
 * The engine does not interpret keys and does not own placeholder vocabularies.
 * Products decide what lives in {@link PersonaSpec#attributes()}.
 */
@FunctionalInterface
public interface PersonaRenderer {

    /**
     * @return system text; {@code null} is treated as blank by {@link ChatPersona#render}
     */
    String render(PersonaSpec spec);
}
