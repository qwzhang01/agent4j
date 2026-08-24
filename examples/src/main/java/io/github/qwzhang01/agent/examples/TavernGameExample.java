package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.memory.InMemoryMemoryStore;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.security.InMemoryAuditLogger;
import io.github.qwzhang01.agent.tavern.TavernGame;
import io.github.qwzhang01.agent.tavern.character.CharacterCard;
import io.github.qwzhang01.agent.tavern.event.EventRule;
import io.github.qwzhang01.agent.tavern.event.GameEvent;
import io.github.qwzhang01.agent.tavern.relation.RelationshipPolicy;
import io.github.qwzhang01.agent.tavern.replay.GameReplay;
import io.github.qwzhang01.agent.tavern.turn.TurnResult;
import io.github.qwzhang01.agent.tavern.world.WorldEffect;
import io.github.qwzhang01.agent.tavern.world.WorldState;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stage 16 acceptance demo: a minimal tavern scene through the full stack -
 * three characters, relationships that move, a story event that fires, the
 * per-turn limiter that self-corrects the model, and a save/reload/replay
 * cycle (blueprint §6, T0-T7).
 * <p>
 * Fully scripted with MockModelClient: the whole game runs with zero LLM
 * dependency. Run with:
 * <pre>mvn exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.TavernGameExample</pre>
 */
public class TavernGameExample {

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path saveRoot = Files.createTempDirectory("tavern-saves");

        // ============ T0. Assembly ============
        System.out.println("=== T0 · Assembling the Golden Oak tavern ===");

        CharacterCard marcus = new CharacterCard("marcus", "Marcus",
                "The barkeep of the Golden Oak. Warm, talkative, knows every rumor in town, "
                        + "speaks in short hearty sentences.", "Welcome to the Golden Oak, traveler.");
        CharacterCard lyra = new CharacterCard("lyra", "Lyra",
                "The resident bard. Sharp-tongued, secretly delighted by genuine appreciation, "
                        + "never admits she is pleased.", null);
        CharacterCard brawn = new CharacterCard("brawn", "Brawn",
                "A mercenary captain nursing a drink in the corner. Speaks rarely, trusts nobody "
                        + "on first meeting.", null);

        EventRule confession = EventRule.once("confession",
                f -> f.relationship("marcus").value() >= 80,
                new GameEvent("confession", "Marcus confesses how much these evenings mean to him.", "marcus"));
        EventRule improvisation = EventRule.once("improvisation",
                f -> f.world().flag("bard-mood").map("lively"::equals).orElse(false)
                        && f.turnNo() >= 4,
                new GameEvent("improvisation", "Lyra strikes up an unannounced improvisation.",
                        "lyra"),
                new WorldEffect.SetFlag("crowd", "cheering"));
        EventRule hostility = EventRule.once("hostility",
                f -> f.relationship("brawn").value() <= 20,
                new GameEvent("hostility", "Brawn's patience with strangers runs out.", "brawn"),
                new WorldEffect.SetFlag("conflict", "brewing"));

        InMemoryAuditLogger gmAudit = new InMemoryAuditLogger();

        MockModelClient model = MockModelClient.scripted()
                // T1 marcus opens
                .respondText("Welcome to the Golden Oak! First drink's on the house, traveler.")
                // T2 marcus: relationship +3, then reply
                .respondToolCalls(ToolCall.of("c1", "adjust_relationship",
                        args(mapper, "marcus", 3)))
                .respondText("You seem the decent sort. Mead, coming right up.")
                // T3 lyra: world flag, then reply
                .respondToolCalls(ToolCall.of("c2", "set_world_flag",
                        args(mapper, "bard-mood", "lively")))
                .respondText("Hmph. Flattery, is it? ...Fine. One song.")
                // T4 brawn's turn (the rule fires at THIS settlement)
                .respondText("Hmph. Walls have ears. Watch yours.")
                // T4 settlement: lyra's event-driven response
                .respondText("(strums) An improvisation it is! Listen well.")
                // T5 marcus: oversized +10 gets rejected, model self-corrects
                .respondToolCalls(ToolCall.of("c3", "adjust_relationship",
                        args(mapper, "marcus", 10)))
                .respondText("Ha - fair enough. Trust is earned sip by sip, not gulped.")
                // T6 (reloaded game) lyra continues
                .respondText("Still here, still sharp. You remember the song, don't you.");

        TavernGame game = TavernGame.builder()
                .modelClient(model)
                .memoryStore(new InMemoryMemoryStore())
                .addCard(marcus)
                .addCard(lyra)
                .addCard(brawn)
                .gameId("golden-oak")
                .initialLocation("great-hall")
                .relationshipPolicy(new RelationshipPolicy(5))
                .addRule(confession)
                .addRule(improvisation)
                .addRule(hostility)
                .governance(gmAudit)
                .storeRoot(saveRoot)
                .build();

        System.out.println("  characters: " + game.characterIds());
        System.out.println("  rules:      confession / improvisation / hostility");
        System.out.println("  policy:     max ±5 net per character per turn");
        System.out.println("  GM backend: governance chain ON, audit ON");

        // ============ T1. Opening ============
        System.out.println("\n=== T1 · Opening: the world greets through a persona ===");
        play(game, "@marcus Good evening! What's the mood tonight?");
        System.out.println("  world now:        " + game.world().describe());
        System.out.println("  marcus relation:  " + game.relationships().view("marcus").describe());

        // ============ T2. Dialogue changes the world ============
        System.out.println("\n=== T2 · Dialogue changes the world: a relationship moves ===");
        play(game, "@marcus Keep one for yourself, you've earned it.");
        System.out.println("  marcus relation:  " + game.relationships().view("marcus").describe());

        // ============ T3. Multi-character switching ============
        System.out.println("\n=== T3 · Another character, another persona, another state ===");
        play(game, "@lyra That song earlier was genuinely lovely.");
        System.out.println("  world now:        " + game.world().describe());

        // ============ T4. A story event fires ============
        System.out.println("\n=== T4 · The world talks back: an event fires at settlement ===");
        play(game, "@brawn Quiet corner tonight?");
        System.out.println("  triggered events: " + game.eventEvaluator().firedEventIds());
        System.out.println("  world now:        " + game.world().describe());

        // ============ T5. The limiter ============
        System.out.println("\n=== T5 · Governance is balance: an oversized move is rejected ===");
        play(game, "@marcus You're the finest barkeep in the realm, truly!");
        System.out.println("  marcus relation:  " + game.relationships().view("marcus").describe()
                + "  (still 53 - the +10 was rejected, the scene continued)");

        // ============ T6. Save and reload ============
        System.out.println("\n=== T6 · Save and reload: the game is a state you can keep ===");
        game.save();
        System.out.println("  saved to: " + saveRoot.resolve("golden-oak"));

        TavernGame reloaded = TavernGame.builder()
                .modelClient(model)
                .memoryStore(new InMemoryMemoryStore())
                .addCard(marcus)
                .addCard(lyra)
                .addCard(brawn)
                .gameId("golden-oak")
                .initialLocation("great-hall")
                .relationshipPolicy(new RelationshipPolicy(5))
                .addRule(confession)
                .addRule(improvisation)
                .addRule(hostility)
                .governance(gmAudit)
                .storeRoot(saveRoot)
                .load();
        play(reloaded, "@lyra Remember me?");
        reloaded.save();   // save again after playing: the disk log now covers the reloaded turn too
        System.out.println("  reloaded world:       " + reloaded.world().describe());
        System.out.println("  reloaded marcus rel:  " + reloaded.relationships().view("marcus").describe());

        // ============ T7. Replay ============
        System.out.println("\n=== T7 · Replay: walk the recording, never re-run the model ===");
        GameReplay replay = reloaded.replayFromDisk();
        for (int t = 1; t <= replay.turnCount(); t++) {
            System.out.println(replay.describeTurn(t));
        }
        System.out.println("  world at turn 3:      " + replay.stateAt(3).world().describe());
        System.out.println("  marcus at turn 3:     " + replay.stateAt(3).relationship("marcus").describe());
        System.out.println("  replay final == save: "
                + (replay.finalState().world().equals(reloaded.world()) ? "YES" : "NO"));

        // ============ GM backend ============
        System.out.println("\n=== GM backend: every world change has an audit trail ===");
        System.out.println("  audited tool calls: " + gmAudit.getAll().size());
        gmAudit.getAll().forEach(e -> System.out.println("  [" + e.status() + "] " + e.toolName()
                + " " + brief(e.args())));

        System.out.println("\n=== The Golden Oak closes. Same Runtime, second domain Profile. ===");
    }

    private static void play(TavernGame game, String input) {
        TurnResult result = game.playerSay(input);
        if (result instanceof TurnResult.Completed completed) {
            completed.turn().responses().forEach(r ->
                    System.out.println("  " + r.characterId() + ": \"" + r.text() + "\""
                            + (r.eventDriven() ? "   [event-driven]" : "")));
        } else if (result instanceof TurnResult.RoutingMiss miss) {
            System.out.println("  (routing miss: " + miss.message()
                    + " available: " + miss.availableCharacters() + ")");
        }
    }

    private static ObjectNode args(ObjectMapper mapper, String characterId, int delta) {
        ObjectNode n = mapper.createObjectNode();
        n.put("characterId", characterId);
        n.put("delta", delta);
        return n;
    }

    private static ObjectNode args(ObjectMapper mapper, String key, String value) {
        ObjectNode n = mapper.createObjectNode();
        n.put("key", key);
        n.put("value", value);
        return n;
    }

    private static String brief(String args) {
        return args.length() > 60 ? args.substring(0, 60) + "..." : args;
    }
}
