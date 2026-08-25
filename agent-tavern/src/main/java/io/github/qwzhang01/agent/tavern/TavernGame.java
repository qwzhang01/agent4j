package io.github.qwzhang01.agent.tavern;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.security.AuditLogger;
import io.github.qwzhang01.agent.security.GovernedToolExecutor;
import io.github.qwzhang01.agent.security.PermissionChecker;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import io.github.qwzhang01.agent.tavern.character.CharacterAgentFactory;
import io.github.qwzhang01.agent.tavern.character.CharacterCard;
import io.github.qwzhang01.agent.tavern.event.EventEvaluator;
import io.github.qwzhang01.agent.tavern.event.EventRule;
import io.github.qwzhang01.agent.tavern.relation.Relationship;
import io.github.qwzhang01.agent.tavern.relation.RelationshipMatrix;
import io.github.qwzhang01.agent.tavern.relation.RelationshipPolicy;
import io.github.qwzhang01.agent.tavern.replay.GameReplay;
import io.github.qwzhang01.agent.tavern.replay.GameStore;
import io.github.qwzhang01.agent.tavern.replay.SaveGame;
import io.github.qwzhang01.agent.tavern.turn.TurnEngine;
import io.github.qwzhang01.agent.tavern.turn.TurnLog;
import io.github.qwzhang01.agent.tavern.turn.TurnResult;
import io.github.qwzhang01.agent.tavern.world.WorldState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One game of the tavern: the assembly facade over the whole Stage 16 stack
 * (M16.5).
 * <p>
 * Everything a game needs in one place: characters, world, relationships,
 * events, governance, memory, save/replay. Under the hood it is the same
 * primitives as ever - {@link TurnEngine} for the pipeline, {@link GameStore}
 * for persistence, {@link CharacterAgentFactory} for card-to-Agent
 * translation - which is the point: the second domain Profile assembles the
 * same Runtime, changing nothing (blueprint D1).
 * <p>
 * The builder defines a GAME (cards, rules, policy, model); {@link #save()}
 * and {@link Builder#load()} move the GAME STATE. A load uses the same
 * assembly with the saved state - the blueprint's "load(dir, blueprint)"
 * sketch, with the blueprint being the builder itself.
 */
public final class TavernGame {

    private final TurnEngine engine;
    private final GameStore store;

    private TavernGame(TurnEngine engine, GameStore store) {
        this.engine = engine;
        this.store = store;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ============ Play ============

    /** Play one turn: "@name what you say". */
    public TurnResult playerSay(String playerInput) {
        return engine.playTurn(playerInput);
    }

    // ============ Views ============

    public String gameId() {
        return engine.gameId();
    }

    /** Current world state (swapped as turns are played). */
    public WorldState world() {
        return engine.world();
    }

    /** The relationship matrix (always present on the facade path). */
    public RelationshipMatrix relationships() {
        return engine.relationships();
    }

    /** The event evaluator (always present on the facade path). */
    public EventEvaluator eventEvaluator() {
        return engine.eventEvaluator();
    }

    /** Registered character ids, in roster order. */
    public List<String> characterIds() {
        return engine.characterIds();
    }

    /** This instance's turn log. */
    public TurnLog turnLog() {
        return engine.turnLog();
    }

    /**
     * In-memory replay of THIS instance's turns. A game reloaded with
     * {@link Builder#load()} replays its post-load turns; for the full
     * history across sessions use {@link #replayFromDisk()}.
     */
    public GameReplay replay() {
        return GameReplay.of(engine.initialWorld(), engine.initialRelationships(),
                engine.turnLog().turns());
    }

    // ============ Persistence ============

    /** Save the game under the configured store root ({@code {root}/{gameId}/}). */
    public Path save() throws IOException {
        requireStore();
        return store.save(engine);
    }

    /** Full-history replay from disk, including turns from previous sessions. */
    public GameReplay replayFromDisk() throws IOException {
        requireStore();
        return store.loadReplay(engine.gameId());
    }

    private void requireStore() {
        if (store == null) {
            throw new IllegalStateException(
                    "no storeRoot configured on the builder - save()/replayFromDisk() need one");
        }
    }

    // ============ Builder ============

    /**
     * Assembles a game: model + memory + roster + world + policy + rules +
     * governance + store. All optional pieces have sensible defaults; the
     * same builder instance can {@link #build()} a fresh game or
     * {@link #load()} a saved one.
     */
    public static final class Builder {

        private ModelClient modelClient;
        private MemoryStore memoryStore = new InMemoryMemoryStore();
        private final List<CharacterCard> cards = new ArrayList<>();
        private String gameId;
        private String initialLocation = "tavern-hall";
        private RelationshipPolicy relationshipPolicy = RelationshipPolicy.DEFAULT;
        private final List<EventRule> rules = new ArrayList<>();
        private Function<ToolRegistry, ToolExecutor> executorFactory;
        private Path storeRoot;

        public Builder modelClient(ModelClient modelClient) {
            this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
            return this;
        }

        public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore must not be null");
            return this;
        }

        public Builder addCard(CharacterCard card) {
            this.cards.add(Objects.requireNonNull(card, "card must not be null"));
            return this;
        }

        public Builder gameId(String gameId) {
            this.gameId = Objects.requireNonNull(gameId, "gameId must not be null");
            return this;
        }

        public Builder initialLocation(String location) {
            this.initialLocation = Objects.requireNonNull(location, "location must not be null");
            return this;
        }

        public Builder relationshipPolicy(RelationshipPolicy policy) {
            this.relationshipPolicy = Objects.requireNonNull(policy, "policy must not be null");
            return this;
        }

        public Builder addRule(EventRule rule) {
            this.rules.add(Objects.requireNonNull(rule, "rule must not be null"));
            return this;
        }

        /**
         * Advanced: full control over the tool executor per character Agent
         * (e.g. a custom governance chain). Mutually overrides
         * {@link #governance(AuditLogger)}.
         */
        public Builder executorFactory(Function<ToolRegistry, ToolExecutor> executorFactory) {
            this.executorFactory = executorFactory;
            return this;
        }

        /**
         * The GM backend (blueprint D4): all game tools run AUTO under a
         * Stage 9 governance chain with full audit into the given logger.
         */
        public Builder governance(AuditLogger auditLogger) {
            Objects.requireNonNull(auditLogger, "auditLogger must not be null");
            this.executorFactory = registry -> GovernedToolExecutor
                    .builder(new DefaultToolExecutor(registry))
                    .permissionChecker(new PermissionChecker(new ToolPolicy(ToolPermission.AUTO)))
                    .auditLogger(auditLogger)
                    .build();
            return this;
        }

        /** Where saves live ({@code {root}/{gameId}/save.json + turn-log.jsonl}). */
        public Builder storeRoot(Path root) {
            this.storeRoot = Objects.requireNonNull(root, "storeRoot must not be null");
            return this;
        }

        /** Start a fresh game. */
        public TavernGame build() {
            validate();
            TurnEngine engine = newEngine(WorldState.initial(initialLocation), null, null);
            return new TavernGame(engine, createStore());
        }

        /**
         * Resume a saved game: same assembly (cards, rules, policy, model),
         * saved state (world, relationships, histories, fired events) - and
         * the game's WHOLE turn history, so a resumed game's own save() still
         * writes a consecutive log from turn 1.
         *
         * @throws java.nio.file.NoSuchFileException when nothing was saved for this game
         */
        public TavernGame load() throws IOException {
            validate();
            GameStore store = createStore();
            if (store == null) {
                throw new IllegalStateException("storeRoot is required for load()");
            }
            SaveGame save = store.loadSave(gameId);
            GameReplay previous = store.loadReplay(gameId);

            CharacterAgentFactory factory = new CharacterAgentFactory(
                    modelClient, new io.github.qwzhang01.agent.tavern.character.CharacterMemory(memoryStore),
                    CharacterAgentFactory.DEFAULT_MAX_STEPS, executorFactory);
            RelationshipMatrix matrix = new RelationshipMatrix(relationshipPolicy);
            matrix.restore(save.relationships());
            EventEvaluator evaluator = new EventEvaluator(rules);
            evaluator.restore(save.firedEventIds());
            TurnEngine engine = TurnEngine.resume(factory, cards, gameId,
                    previous.initialWorld(), previous.initialRelationships(),
                    save.world(), previous.turns(),
                    matrix, evaluator);
            engine.restoreHistories(save.characterHistories());
            return new TavernGame(engine, store);
        }

        // ============ Internals ============

        private void validate() {
            if (modelClient == null) {
                throw new IllegalStateException("modelClient is required");
            }
            if (gameId == null || gameId.isBlank()) {
                throw new IllegalStateException("gameId is required");
            }
            if (cards.isEmpty()) {
                throw new IllegalStateException("at least one character card is required");
            }
        }

        private TurnEngine newEngine(WorldState world,
                                     Map<String, Relationship> restoredRelationships,
                                     Set<String> restoredFiredEvents) {
            CharacterAgentFactory factory = new CharacterAgentFactory(
                    modelClient, new io.github.qwzhang01.agent.tavern.character.CharacterMemory(memoryStore),
                    CharacterAgentFactory.DEFAULT_MAX_STEPS, executorFactory);
            RelationshipMatrix matrix = new RelationshipMatrix(relationshipPolicy);
            if (restoredRelationships != null) {
                matrix.restore(restoredRelationships);
            }
            EventEvaluator evaluator = new EventEvaluator(rules);
            if (restoredFiredEvents != null) {
                evaluator.restore(restoredFiredEvents);
            }
            return new TurnEngine(factory, cards, gameId, world, matrix, evaluator);
        }

        private GameStore createStore() {
            return storeRoot != null ? new GameStore(storeRoot) : null;
        }
    }
}
