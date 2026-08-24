package io.github.qwzhang01.agent.tavern.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.tavern.relation.Relationship;
import io.github.qwzhang01.agent.tavern.turn.Turn;
import io.github.qwzhang01.agent.tavern.world.WorldEffect;
import io.github.qwzhang01.agent.tavern.world.WorldState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single codec for the save/replay file formats (Stage 16 M16.4) -
 * the domain cousin of Stage 14's TrajectoryCodec.
 * <p>
 * Hand-built JSON trees, not annotation magic: the file format is a contract,
 * and contracts live in exactly one place. {@code GameStore} writes through
 * this codec, {@code GameReplayer} reads through it - one format, one
 * authority.
 * <p>
 * Formats:
 * <pre>
 * save.json      := { gameId, world{turnCount,location,flags{}},
 *                     relationships{id{value,lastChangedTurn}},
 *                     character_histories{id[msg]},
 *                     fired_event_ids[] }
 * turn-log.jsonl := line 1  {"kind":"initial", world{}, relationships{}}
 *                   line n  {"kind":"turn", turnNo, playerInput,
 *                            speakingCharacterId, responses[], appliedEffects[],
 *                            relationshipChanges[], triggeredEventIds[], timestamp}
 * message        := { role, content, toolCalls?[{id,name,args}], toolCallId?, name? }
 * effect         := { type:"SetFlag",key,value } | { type:"ClearFlag",key }
 *                  | { type:"SetLocation",location }
 * </pre>
 * v1 honest boundary: text-only saves. A message carrying multimodal parts
 * fails loud instead of being silently dropped.
 */
final class ReplayCodec {

    private ReplayCodec() {
    }

    // ============ World ============

    static ObjectNode worldToJson(WorldState world, ObjectMapper m) {
        ObjectNode n = m.createObjectNode();
        n.put("turnCount", world.turnCount());
        n.put("location", world.location());
        ObjectNode flags = n.putObject("flags");
        world.flags().forEach(flags::put);
        return n;
    }

    static WorldState worldFromJson(JsonNode n) {
        Map<String, String> flags = new LinkedHashMap<>();
        n.path("flags").fields().forEachRemaining(e -> flags.put(e.getKey(), e.getValue().asText()));
        return new WorldState(n.path("turnCount").asInt(), n.path("location").asText(), flags);
    }

    // ============ Relationship ============

    static ObjectNode relationshipToJson(Relationship r, ObjectMapper m) {
        ObjectNode n = m.createObjectNode();
        n.put("value", r.value());
        n.put("lastChangedTurn", r.lastChangedTurn());
        return n;
    }

    static Relationship relationshipFromJson(JsonNode n) {
        return new Relationship(n.path("value").asInt(), n.path("lastChangedTurn").asInt());
    }

    static ObjectNode relationshipsToJson(Map<String, Relationship> relationships, ObjectMapper m) {
        ObjectNode n = m.createObjectNode();
        relationships.forEach((id, r) -> n.set(id, relationshipToJson(r, m)));
        return n;
    }

    static Map<String, Relationship> relationshipsFromJson(JsonNode n) {
        Map<String, Relationship> out = new LinkedHashMap<>();
        n.fields().forEachRemaining(e -> out.put(e.getKey(), relationshipFromJson(e.getValue())));
        return out;
    }

    // ============ ChatMessage ============

    static ObjectNode messageToJson(ChatMessage msg, ObjectMapper m) {
        if (msg.parts() != null) {
            throw new IllegalArgumentException(
                    "multimodal messages are not saveable in v1 (text-only saves)");
        }
        ObjectNode n = m.createObjectNode();
        n.put("role", msg.role().name());
        if (msg.content() != null) {
            n.put("content", msg.content());
        }
        if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            ArrayNode arr = n.putArray("toolCalls");
            for (ToolCall tc : msg.toolCalls()) {
                ObjectNode t = arr.addObject();
                t.put("id", tc.id());
                t.put("name", tc.name());
                t.set("args", tc.arguments());
            }
        }
        if (msg.toolCallId() != null) {
            n.put("toolCallId", msg.toolCallId());
        }
        if (msg.name() != null) {
            n.put("name", msg.name());
        }
        return n;
    }

    static ChatMessage messageFromJson(JsonNode n) {
        ChatRole role = ChatRole.valueOf(n.path("role").asText());
        String content = n.hasNonNull("content") ? n.get("content").asText() : null;
        if (role == ChatRole.SYSTEM) {
            return ChatMessage.system(content);
        }
        if (role == ChatRole.USER) {
            return ChatMessage.user(content);
        }
        if (role == ChatRole.ASSISTANT) {
            if (n.has("toolCalls") && n.get("toolCalls").isArray()) {
                List<ToolCall> calls = new ArrayList<>();
                for (JsonNode tc : n.get("toolCalls")) {
                    calls.add(ToolCall.of(
                            tc.path("id").asText(),
                            tc.path("name").asText(),
                            tc.path("args")));
                }
                return ChatMessage.assistantWithTools(content, calls);
            }
            return ChatMessage.assistant(content);
        }
        // TOOL
        String toolCallId = n.path("toolCallId").asText(null);
        String name = n.hasNonNull("name") ? n.get("name").asText() : null;
        return name != null
                ? ChatMessage.tool(toolCallId, name, content)
                : ChatMessage.tool(toolCallId, content);
    }

    static ArrayNode messagesToJson(List<ChatMessage> messages, ObjectMapper m) {
        ArrayNode arr = m.createArrayNode();
        messages.forEach(msg -> arr.add(messageToJson(msg, m)));
        return arr;
    }

    static List<ChatMessage> messagesFromJson(JsonNode n) {
        List<ChatMessage> out = new ArrayList<>();
        for (JsonNode msgNode : n) {
            out.add(messageFromJson(msgNode));
        }
        return out;
    }

    // ============ WorldEffect ============

    static ObjectNode effectToJson(WorldEffect e, ObjectMapper m) {
        ObjectNode n = m.createObjectNode();
        if (e instanceof WorldEffect.SetFlag f) {
            n.put("type", "SetFlag");
            n.put("key", f.key());
            n.put("value", f.value());
        } else if (e instanceof WorldEffect.ClearFlag c) {
            n.put("type", "ClearFlag");
            n.put("key", c.key());
        } else if (e instanceof WorldEffect.SetLocation l) {
            n.put("type", "SetLocation");
            n.put("location", l.location());
        } else {
            throw new IllegalArgumentException("unknown effect: " + e);
        }
        return n;
    }

    static WorldEffect effectFromJson(JsonNode n) {
        String type = n.path("type").asText();
        if ("SetFlag".equals(type)) {
            return new WorldEffect.SetFlag(n.path("key").asText(), n.path("value").asText());
        }
        if ("ClearFlag".equals(type)) {
            return new WorldEffect.ClearFlag(n.path("key").asText());
        }
        if ("SetLocation".equals(type)) {
            return new WorldEffect.SetLocation(n.path("location").asText());
        }
        throw new IllegalArgumentException("unknown effect type: " + type);
    }

    // ============ Turn ============

    static ObjectNode turnToJson(Turn t, ObjectMapper m) {
        ObjectNode n = m.createObjectNode();
        n.put("kind", "turn");
        n.put("turnNo", t.turnNo());
        n.put("playerInput", t.playerInput());
        n.put("speakingCharacterId", t.speakingCharacterId());
        ArrayNode responses = n.putArray("responses");
        for (Turn.CharacterResponse r : t.responses()) {
            ObjectNode rn = responses.addObject();
            rn.put("characterId", r.characterId());
            rn.put("text", r.text());
            rn.put("eventDriven", r.eventDriven());
        }
        ArrayNode effects = n.putArray("appliedEffects");
        for (Turn.WorldEffectEntry e : t.appliedEffects()) {
            effects.add(effectToJson(e.effect(), m));
        }
        ArrayNode changes = n.putArray("relationshipChanges");
        for (Turn.RelationshipChange c : t.relationshipChanges()) {
            ObjectNode cn = changes.addObject();
            cn.put("characterId", c.characterId());
            cn.put("delta", c.delta());
            cn.put("before", c.before());
            cn.put("after", c.after());
        }
        ArrayNode events = n.putArray("triggeredEventIds");
        t.triggeredEventIds().forEach(events::add);
        n.put("timestamp", t.timestamp().toString());
        return n;
    }

    static Turn turnFromJson(JsonNode n) {
        List<Turn.CharacterResponse> responses = new ArrayList<>();
        for (JsonNode r : n.path("responses")) {
            responses.add(new Turn.CharacterResponse(
                    r.path("characterId").asText(),
                    r.path("text").asText(),
                    r.path("eventDriven").asBoolean(false)));
        }
        List<Turn.WorldEffectEntry> effects = new ArrayList<>();
        for (JsonNode e : n.path("appliedEffects")) {
            effects.add(new Turn.WorldEffectEntry(effectFromJson(e)));
        }
        List<Turn.RelationshipChange> changes = new ArrayList<>();
        for (JsonNode c : n.path("relationshipChanges")) {
            changes.add(new Turn.RelationshipChange(
                    c.path("characterId").asText(),
                    c.path("delta").asInt(),
                    c.path("before").asInt(),
                    c.path("after").asInt()));
        }
        List<String> eventIds = new ArrayList<>();
        n.path("triggeredEventIds").forEach(id -> eventIds.add(id.asText()));
        return new Turn(
                n.path("turnNo").asInt(),
                n.path("playerInput").asText(),
                n.path("speakingCharacterId").asText(),
                responses,
                effects,
                changes,
                eventIds,
                Instant.parse(n.path("timestamp").asText()));
    }

    // ============ Envelope Lines ============

    static ObjectNode initialLineToJson(WorldState world,
                                        Map<String, Relationship> relationships,
                                        ObjectMapper m) {
        ObjectNode n = m.createObjectNode();
        n.put("kind", "initial");
        n.set("world", worldToJson(world, m));
        n.set("relationships", relationshipsToJson(relationships, m));
        return n;
    }

    static boolean isInitialLine(JsonNode n) {
        return "initial".equals(n.path("kind").asText(null));
    }

    static boolean isTurnLine(JsonNode n) {
        return "turn".equals(n.path("kind").asText(null));
    }
}
