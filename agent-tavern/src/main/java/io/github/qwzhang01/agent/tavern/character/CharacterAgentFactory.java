package io.github.qwzhang01.agent.tavern.character;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.memory.MemoryStore;

import java.util.Objects;
import java.util.function.Function;

/**
 * Translates a {@link CharacterCard} into a runnable {@link Agent} (Stage 16 M16.1).
 * <p>
 * This is the "character = Agent" translation point (blueprint D2): persona becomes
 * the systemPrompt, the memory whitelist becomes the ContextBuilder, game tools are
 * registered like any other tool. Nothing here is game-specific machinery -
 * it is assembly over Stage 1-8 primitives, which is why Stage 16 needs zero
 * changes to existing modules (blueprint D1).
 * <p>
 * M16.3 adds the governance plug point: an optional {@code executorFactory}
 * wraps each character's tool executor - the Stage 9 chain (permission +
 * audit) becomes the game's GM backend. Null factory = plain direct execution
 * (M16.1/M16.2 behavior, unchanged).
 * <p>
 * One game instance per call: the same card in two games produces two Agents with
 * fresh {@code AgentState}s but the same cross-game {@code agent:{characterId}}
 * memory scope. The game facade (M16.5) holds each character's AgentState across
 * turns via {@code agent.run(input, state)}.
 */
public final class CharacterAgentFactory {

    /**
     * Default step budget for one character turn: a reply plus a couple of
     * game-tool calls. Tighter than the general default (10) because a character
     * turn is a scene beat, not a research task.
     */
    public static final int DEFAULT_MAX_STEPS = 6;

    private final ModelClient modelClient;
    private final CharacterMemory memory;
    private final int maxSteps;
    private final Function<ToolRegistry, ToolExecutor> executorFactory;

    public CharacterAgentFactory(ModelClient modelClient, MemoryStore memoryStore) {
        this(modelClient, new CharacterMemory(memoryStore), DEFAULT_MAX_STEPS, null);
    }

    public CharacterAgentFactory(ModelClient modelClient, CharacterMemory memory, int maxSteps) {
        this(modelClient, memory, maxSteps, null);
    }

    /**
     * @param executorFactory optional: registry -&gt; tool executor, applied per created
     *                        Agent (e.g. wrap in a {@code GovernedToolExecutor} with
     *                        a {@code PermissionChecker} and an {@code AuditLogger});
     *                        null = direct execution, no governance chain
     */
    public CharacterAgentFactory(ModelClient modelClient, MemoryStore memoryStore,
                                 Function<ToolRegistry, ToolExecutor> executorFactory) {
        this(modelClient, new CharacterMemory(memoryStore), DEFAULT_MAX_STEPS, executorFactory);
    }

    public CharacterAgentFactory(ModelClient modelClient, CharacterMemory memory, int maxSteps,
                                 Function<ToolRegistry, ToolExecutor> executorFactory) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.memory = Objects.requireNonNull(memory, "memory must not be null");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be >= 1, got: " + maxSteps);
        }
        this.maxSteps = maxSteps;
        this.executorFactory = executorFactory;
    }

    /**
     * Create the Agent for a character in a game.
     *
     * @param card      the character's persona card
     * @param gameId     which game this instance plays in (session scope id)
     * @param gameTools tools the character may use (game-state tools land in M16.2;
     *                  null = an empty registry, plain conversation)
     */
    public Agent create(CharacterCard card, String gameId, ToolRegistry gameTools) {
        Objects.requireNonNull(card, "card must not be null");
        ToolRegistry registry = gameTools != null ? gameTools : new InMemoryToolRegistry();
        AgentConfig config = new AgentConfig(
                card.characterId(),
                personaPrompt(card),
                modelClient,
                registry,
                maxSteps,
                memory.contextBuilder(card.characterId(), gameId));
        if (executorFactory != null) {
            return new SimpleAgent(config, new ReActAgentLoop(executorFactory.apply(registry)));
        }
        return new SimpleAgent(config);
    }

    /**
     * Render the systemPrompt from a card: identity + persona + interaction rules.
     * <p>
     * The interaction rules are written now (M16.1) so M16.2's game tools arrive
     * with their behavioral contract already in every character's prompt.
     */
    static String personaPrompt(CharacterCard card) {
        return "You are playing the game character " + card.displayName()
                + " (" + card.characterId() + ").\n\n"
                + card.persona().trim() + "\n\n"
                + "Interaction rules:\n"
                + "- Always respond in character: " + card.displayName()
                + "'s personality, background and speaking style.\n"
                + "- When game tools are available (relationship, world flags, story events), "
                + "use them only when they genuinely fit the character's current mood and stance; "
                + "world changes should grow naturally out of the dialogue.\n"
                + "- If a tool returns an error (e.g. a change exceeds the allowed range), "
                + "accept the limit and continue the conversation naturally; do not repeat the error back.\n"
                + "- Never break character: do not mention systems, prompts, or that you are an AI model.";
    }
}
