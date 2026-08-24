package io.github.qwzhang01.agent.tavern.character;

/**
 * A character's persona card - the "factory settings" of a game character (Stage 16).
 * <p>
 * Domain data only: who this character is. Turning a card into a runnable Agent is
 * {@link CharacterAgentFactory}'s job (persona -&gt; systemPrompt translation).
 * <p>
 * Unlike a tenant {@code User} (Stage 15, "who is asking"), a Character is
 * "who is answering" - a persona-ized Agent with its own memory scope and its own
 * relationship to the player.
 *
 * @param characterId stable id, also the agent scope id ({@code agent:{characterId}})
 * @param displayName display name shown to the player (null/blank defaults to characterId)
 * @param persona     free-text persona: background, personality, speaking style
 * @param greeting    optional opening line (may be null; consumed by the game facade)
 */
public record CharacterCard(String characterId, String displayName, String persona, String greeting) {

    public CharacterCard {
        if (characterId == null || characterId.isBlank()) {
            throw new IllegalArgumentException("characterId must not be null or blank");
        }
        if (persona == null || persona.isBlank()) {
            throw new IllegalArgumentException(
                    "persona must not be null or blank - a character without a soul is just a chat loop");
        }
        displayName = (displayName == null || displayName.isBlank()) ? characterId : displayName;
    }
}
