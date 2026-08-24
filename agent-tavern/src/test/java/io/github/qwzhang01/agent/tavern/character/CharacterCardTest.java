package io.github.qwzhang01.agent.tavern.character;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stage 16 M16.1: {@link CharacterCard} is pure domain data - validation only.
 */
class CharacterCardTest {

    @Test
    @DisplayName("blank displayName defaults to characterId; greeting stays optional")
    void displayNameDefaultsToId() {
        CharacterCard card = new CharacterCard("marcus", null, "A warm-hearted barkeep.", null);

        assertEquals("marcus", card.displayName());
        assertNull(card.greeting(), "greeting is optional domain data, not a persona requirement");
    }

    @Test
    @DisplayName("blank characterId is rejected")
    void blankIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CharacterCard("  ", "Marcus", "persona", "hi"));
    }

    @Test
    @DisplayName("blank persona is rejected - a character without a soul is just a chat loop")
    void blankPersonaRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CharacterCard("marcus", "Marcus", "", "hi"));
    }

    @Test
    @DisplayName("null persona is rejected with the same rule")
    void nullPersonaRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CharacterCard("marcus", "Marcus", null, "hi"));
    }
}
