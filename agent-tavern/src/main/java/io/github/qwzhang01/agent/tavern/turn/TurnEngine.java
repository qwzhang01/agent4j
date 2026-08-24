package io.github.qwzhang01.agent.tavern.turn;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.tavern.character.CharacterAgentFactory;
import io.github.qwzhang01.agent.tavern.character.CharacterCard;
import io.github.qwzhang01.agent.tavern.event.EventEvaluator;
import io.github.qwzhang01.agent.tavern.event.GameFacts;
import io.github.qwzhang01.agent.tavern.event.TriggerEventTool;
import io.github.qwzhang01.agent.tavern.relation.AdjustRelationshipTool;
import io.github.qwzhang01.agent.tavern.relation.Relationship;
import io.github.qwzhang01.agent.tavern.relation.RelationshipMatrix;
import io.github.qwzhang01.agent.tavern.world.SetWorldFlagTool;
import io.github.qwzhang01.agent.tavern.world.WorldEffect;
import io.github.qwzhang01.agent.tavern.world.WorldState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The turn pipeline: routing -&gt; context injection -&gt; character run -&gt;
 * event settlement -&gt; logging (Stage 16, blueprint D8: a fixed sequence,
 * not a graph).
 * <p>
 * Pipeline in full (M16.3):
 * <ol>
 *   <li>mention routing: the first {@code @id} matching a registered character;
 *       a miss returns {@link TurnResult.RoutingMiss} without a model call,
 *       without advancing the turn count, without logging</li>
 *   <li>world advances one turn; the input is prefixed with {@code [world]} (and
 *       {@code [relationship]} - derived from the matrix unless overridden) -
 *       the same sticky-note technique as Stage 12's {@code [from userId]}</li>
 *   <li>the speaking character's Agent runs with its held AgentState; game tools
 *       submit world effects to the engine's single apply point; relationship
 *       adjustments go through the matrix's per-turn limiter; manual event
 *       triggers are QUEUED (never executed inline - no recursion storms)</li>
 *   <li>event settlement, exactly one pass: rules evaluated against the
 *       post-response facts + queued manual triggers, in order. Each fired
 *       event applies its effects and lets its respondCharacter answer in
 *       this same turn (eventDriven = true). Effects from this batch never
 *       re-trigger evaluation this turn; manual triggers queued during an
 *       event response roll over to the next settlement</li>
 *   <li>the settled {@link Turn} is appended to the {@link TurnLog}</li>
 * </ol>
 * <p>
 * M16.2 scope note: this engine holds the game's state internally (world,
 * relationships, per-character AgentStates, log). The M16.5 {@code TavernGame}
 * facade will own that state and drive the engine; the pipeline itself will
 * not change.
 */
public final class TurnEngine {

    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_-]+)");

    private final CharacterAgentFactory agentFactory;
    private final String gameId;
    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Map<String, AgentState> agentStates = new LinkedHashMap<>();
    private final Function<String, String> relationshipDescriber;
    private final RelationshipMatrix relationships;
    private final EventEvaluator eventEvaluator;
    private final TurnLog turnLog = new TurnLog();
    /** The state at game start - the replay's initial envelope (M16.4). */
    private final WorldState initialWorld;
    private final Map<String, Relationship> initialRelationships;

    private WorldState world;
    /** Effects applied during the turn currently being played. */
    private final List<WorldEffect> turnEffects = new ArrayList<>();
    /** Relationship changes accepted during the turn currently being played. */
    private final List<Turn.RelationshipChange> turnRelationshipChanges = new ArrayList<>();
    /** Events fired during the turn currently being played. */
    private final List<String> triggeredEventIds = new ArrayList<>();
    /** Manual event triggers queued during the turn, fired at settlement. */
    private final List<EventEvaluator.TriggeredEvent> pendingManualEvents = new ArrayList<>();

    /**
     * Full constructor: separates the log's beginning (game initial) from the
     * current world, so a resumed engine can carry the game's WHOLE history.
     */
    private TurnEngine(CharacterAgentFactory agentFactory, List<CharacterCard> cards,
                       String gameId, WorldState gameInitialWorld,
                       Map<String, Relationship> gameInitialRelationships,
                       WorldState currentWorld, List<Turn> previousTurns,
                       RelationshipMatrix relationships, EventEvaluator eventEvaluator,
                       Function<String, String> relationshipDescriber) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory must not be null");
        this.gameId = Objects.requireNonNull(gameId, "gameId must not be null");
        this.world = Objects.requireNonNull(currentWorld, "currentWorld must not be null");
        this.relationships = relationships;
        this.eventEvaluator = eventEvaluator;
        this.relationshipDescriber = relationshipDescriber != null
                ? relationshipDescriber
                : (relationships != null ? id -> relationships.view(id).describe() : null);
        if (cards == null || cards.isEmpty()) {
            throw new IllegalArgumentException("cards must contain at least one character");
        }
        this.initialWorld = Objects.requireNonNull(gameInitialWorld, "gameInitialWorld must not be null");
        this.initialRelationships = gameInitialRelationships == null
                ? Map.of()
                : Map.copyOf(gameInitialRelationships);
        if (previousTurns != null) {
            previousTurns.forEach(turnLog::append);
        }

        // Game tools are registered by the engine: a mutable world (and a
        // relationship matrix, and an event table) exists only where there
        // is an engine to apply and record - tools submit instructions to
        // this engine's single apply point.
        ToolRegistry gameTools = new InMemoryToolRegistry();
        gameTools.register(new SetWorldFlagTool(this::submitEffect));
        if (relationships != null) {
            gameTools.register(new AdjustRelationshipTool(
                    relationships, () -> world.turnCount(), this::submitRelationshipChange));
        }
        if (eventEvaluator != null) {
            gameTools.register(new TriggerEventTool(eventEvaluator, this::queueManualEvent));
        }

        for (CharacterCard card : cards) {
            agents.put(card.characterId(), agentFactory.create(card, gameId, gameTools));
            agentStates.put(card.characterId(), new AgentState());
        }
    }

    /**
     * New game: the log's beginning and the current world are the same place,
     * and the log starts empty.
     *
     * @param agentFactory          translates cards into runnable Agents
     * @param cards                 the game's character roster (non-empty)
     * @param gameId                session scope id for character memory
     * @param initialWorld          the world at game start
     * @param relationships         relationship matrix (null = no relationship tool)
     * @param eventEvaluator        event rules (null = no event tool, no settlement)
     * @param relationshipDescriber optional override for the [relationship] note
     */
    public TurnEngine(CharacterAgentFactory agentFactory, List<CharacterCard> cards,
                      String gameId, WorldState initialWorld,
                      RelationshipMatrix relationships, EventEvaluator eventEvaluator,
                      Function<String, String> relationshipDescriber) {
        this(agentFactory, cards, gameId, initialWorld,
                relationships != null ? relationships.snapshot() : Map.of(),
                initialWorld, List.of(),
                relationships, eventEvaluator, relationshipDescriber);
    }

    /**
     * Resume a saved game (M16.5): the engine carries the game's WHOLE history -
     * the log's true beginning (initial world + initial relationships), the
     * saved endpoint as the current world, and the previously settled turns
     * pre-filled into the log. A resumed engine's save() therefore writes a
     * consecutive log from turn 1, and its in-memory replay() covers every
     * turn ever played.
     *
     * @param gameInitialWorld         the log's line-1 world (from the previous log)
     * @param gameInitialRelationships the log's line-1 relationships
     * @param currentWorld             the saved endpoint (play continues from here)
     * @param previousTurns            the settled turns from the previous log
     */
    public static TurnEngine resume(CharacterAgentFactory agentFactory, List<CharacterCard> cards,
                                    String gameId,
                                    WorldState gameInitialWorld,
                                    Map<String, Relationship> gameInitialRelationships,
                                    WorldState currentWorld,
                                    List<Turn> previousTurns,
                                    RelationshipMatrix relationships,
                                    EventEvaluator eventEvaluator) {
        return new TurnEngine(agentFactory, cards, gameId, gameInitialWorld,
                gameInitialRelationships, currentWorld, previousTurns,
                relationships, eventEvaluator, null);
    }

    /** No relationships, no events - plain M16.2 pipeline. */
    public TurnEngine(CharacterAgentFactory agentFactory, List<CharacterCard> cards,
                      String gameId, WorldState initialWorld) {
        this(agentFactory, cards, gameId, initialWorld, null, null, null);
    }

    /** Relationships and events, default [relationship] notes derived from the matrix. */
    public TurnEngine(CharacterAgentFactory agentFactory, List<CharacterCard> cards,
                      String gameId, WorldState initialWorld,
                      RelationshipMatrix relationships, EventEvaluator eventEvaluator) {
        this(agentFactory, cards, gameId, initialWorld, relationships, eventEvaluator, null);
    }

    /** M16.2-compatible: explicit describer, no matrix, no events. */
    public TurnEngine(CharacterAgentFactory agentFactory, List<CharacterCard> cards,
                      String gameId, WorldState initialWorld,
                      Function<String, String> relationshipDescriber) {
        this(agentFactory, cards, gameId, initialWorld, null, null, relationshipDescriber);
    }

    // ============ The Pipeline ============

    /**
     * Play one turn of the game.
     */
    public TurnResult playTurn(String playerInput) {
        Objects.requireNonNull(playerInput, "playerInput must not be null");
        String mentioned = resolveMention(playerInput);
        if (mentioned == null) {
            return new TurnResult.RoutingMiss(playerInput,
                    "No character mentioned. Address someone with @name.",
                    List.copyOf(agents.keySet()));
        }

        // 1. enter the next turn and start fresh batches
        this.world = this.world.nextTurn();
        this.turnEffects.clear();
        this.turnRelationshipChanges.clear();
        this.triggeredEventIds.clear();
        List<Turn.CharacterResponse> responses = new ArrayList<>();

        // 2. sticky-note injection, then the character's reply
        Agent agent = agents.get(mentioned);
        AgentState state = agentStates.get(mentioned);
        String reply = agent.run(injectContext(playerInput, mentioned), state);
        responses.add(new Turn.CharacterResponse(mentioned, reply, false));

        // 3. event settlement: exactly one pass (rules + queued manual triggers)
        if (eventEvaluator != null) {
            List<EventEvaluator.TriggeredEvent> batch =
                    new ArrayList<>(eventEvaluator.evaluate(currentFacts()));
            batch.addAll(pendingManualEvents);
            pendingManualEvents.clear();
            for (EventEvaluator.TriggeredEvent triggered : batch) {
                fireEvent(triggered, responses);
            }
        }

        // 4. settle: log the turn
        List<Turn.WorldEffectEntry> applied = turnEffects.stream()
                .map(Turn.WorldEffectEntry::new)
                .toList();
        Turn turn = new Turn(world.turnCount(), playerInput, mentioned,
                List.copyOf(responses), applied, List.copyOf(turnRelationshipChanges),
                List.copyOf(triggeredEventIds), Instant.now());
        turnLog.append(turn);
        return new TurnResult.Completed(turn);
    }

    // ============ Views ============

    /** Which game this engine is playing (save-file index, M16.4). */
    public String gameId() {
        return gameId;
    }

    /** Current world state (immutable value; the engine swaps it as turns are played). */
    public WorldState world() {
        return world;
    }

    /** The world at game start - the replay's initial envelope (M16.4). */
    public WorldState initialWorld() {
        return initialWorld;
    }

    /** Relationships at game start (post-construction, pre-first-turn; M16.4). */
    public Map<String, Relationship> initialRelationships() {
        return initialRelationships;
    }

    /** Each character's dialogue history (AgentState messages, copied; M16.4 save view). */
    public Map<String, List<ChatMessage>> characterHistories() {
        Map<String, List<ChatMessage>> out = new LinkedHashMap<>();
        agentStates.forEach((id, state) -> out.put(id, List.copyOf(state.getMessages())));
        return out;
    }

    /**
     * Restore dialogue histories from a save (M16.4 load path): replaces each
     * character's messages with the saved ones, so a reloaded game continues
     * conversations with full context.
     */
    public void restoreHistories(Map<String, List<ChatMessage>> histories) {
        if (histories == null) {
            return;
        }
        histories.forEach((id, messages) -> {
            AgentState state = agentStates.get(id);
            if (state == null) {
                throw new IllegalArgumentException(
                        "cannot restore history for unknown character: " + id);
            }
            state.getMessages().clear();
            if (messages != null) {
                messages.forEach(state::addMessage);
            }
        });
    }

    /** The relationship matrix, when the game has one (null otherwise). */
    public RelationshipMatrix relationships() {
        return relationships;
    }

    /** The event evaluator, when the game has one (null otherwise). */
    public EventEvaluator eventEvaluator() {
        return eventEvaluator;
    }

    /** The append-only turn log of this game. */
    public TurnLog turnLog() {
        return turnLog;
    }

    /** Registered character ids, in roster order. */
    public List<String> characterIds() {
        return List.copyOf(agents.keySet());
    }

    // ============ Internals ============

    /**
     * The single point where world effects are applied AND recorded.
     * Tools (and event consequences) submit here; nothing else touches the
     * world field during a turn.
     */
    private void submitEffect(WorldEffect effect) {
        this.world = this.world.apply(effect);
        this.turnEffects.add(effect);
    }

    /** Accepted relationship adjustments land in the turn record here (replay stream). */
    private void submitRelationshipChange(RelationshipMatrix.ApplyResult.Applied applied) {
        this.turnRelationshipChanges.add(new Turn.RelationshipChange(
                applied.characterId(), applied.requestedDelta(),
                applied.before().value(), applied.after().value()));
    }

    /** Manual trigger queue - fired at settlement, never inline. */
    private void queueManualEvent(EventEvaluator.TriggeredEvent triggered) {
        pendingManualEvents.add(triggered);
    }

    /**
     * Fire one triggered event: apply its carried effects, remember it, and
     * let its respondCharacter answer within this same turn (eventDriven).
     * Event responses are queued to the SAME batch rule set: a manual trigger
     * issued during an event response lands in the NEXT settlement (dramatic
     * delay, and no recursion).
     */
    private void fireEvent(EventEvaluator.TriggeredEvent triggered,
                           List<Turn.CharacterResponse> responses) {
        for (WorldEffect effect : triggered.effects()) {
            submitEffect(effect);
        }
        triggeredEventIds.add(triggered.event().eventId());
        String respondId = triggered.event().respondCharacterId();
        if (respondId != null && agents.containsKey(respondId)) {
            String input = "[event] " + triggered.event().description();
            String text = agents.get(respondId).run(input, agentStates.get(respondId));
            responses.add(new Turn.CharacterResponse(respondId, text, true));
        }
    }

    private GameFacts currentFacts() {
        Map<String, Relationship> rel = relationships != null ? relationships.snapshot() : Map.of();
        return new GameFacts(world, rel, world.turnCount());
    }

    private String injectContext(String playerInput, String characterId) {
        StringBuilder sb = new StringBuilder();
        sb.append("[world] ").append(world.describe()).append('\n');
        if (relationshipDescriber != null) {
            String rel = relationshipDescriber.apply(characterId);
            if (rel != null && !rel.isBlank()) {
                sb.append("[relationship] ").append(rel).append('\n');
            }
        }
        sb.append("[player] ").append(playerInput);
        return sb.toString();
    }

    /**
     * First {@code @id} token that matches a registered character.
     * Stage 12 autoDetect semantics: an id must be followed by a separator
     * (the regex stops at anything outside [A-Za-z0-9_-], so {@code @marcus,}
     * matches "marcus" while {@code @marcusville} does not match "marcus").
     */
    private String resolveMention(String input) {
        Matcher m = MENTION.matcher(input);
        while (m.find()) {
            String candidate = m.group(1);
            if (agents.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
