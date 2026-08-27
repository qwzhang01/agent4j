package io.github.qwzhang01.agent.chat.persona;

import io.github.qwzhang01.agent.chat.model.PersonaSpec;

/**
 * Turns structured persona attributes into system text.
 * <p>
 * The engine does not interpret keys and does not own placeholder vocabularies.
 * Products decide what lives in {@link PersonaSpec#attributes()}.
 */
@FunctionalInterface
public interface PersonaRenderer {

    /**
     * @return system text; {@code null} is treated as blank by {@link io.github.qwzhang01.agent.chat.model.ChatPersona#render}
     */
    String render(PersonaSpec spec);
}
