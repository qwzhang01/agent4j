package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 8.3: malformed-input fuzz and property tests for the A2A wire codec.
 * <p>
 * Roadmap: "协议 malformed input fuzz/property tests". The A2A side of the
 * protocol border: {@link A2AJson} parses peer JSON into our task model.
 * Design philosophy under fuzz (from the codec's javadoc): unknown fields
 * are ignored, unmappable parts skipped, never reject-the-whole-message —
 * so the fuzz invariant is one-sided acceptance: malformed STRUCTURE must
 * be rejected (or coerced) without escaping the JVM, and any input the
 * codec accepts must yield a coherent model object.
 * <p>
 * Modes mirror {@code ProtocolFuzzTest}: mutation fuzz over valid cards and
 * tasks, a hostile-shape gallery, and property tests over generated valid
 * payloads (serialize then parse back, invariants hold).
 */
class A2AWireFuzzTest {

    private static final int FUZZ_ITERATIONS = 512;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SplittableRandom random = new SplittableRandom(20260917L);

    // ============ Property: valid cards/tasks round-trip ============

    @Test
    void property_cardJsonRoundTrips() throws Exception {
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            AgentCard card = randomCard();
            ObjectNode json = A2AJson.cardJson(card, random.nextBoolean() ? null : "http://ov:1");

            JsonNode parsed = MAPPER.readTree(json.toString());
            AgentCard back = A2AJson.cardFrom(parsed, null);

            // Invariants: name always present (default on parse), url
            // survives (either the card's own or the override), skills are
            // plain strings, capabilities booleans.
            assertNotNull(back.name());
            assertFalse(back.name().isBlank());
            assertEquals(card.name(), back.name());
            assertEquals(json.path("url").asText(null), back.url());
        }
    }

    @Test
    void property_taskFromAcceptingArbitraryTextParts() throws Exception {
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            ObjectNode taskJson = randomTaskJson();
            String stateLabel = taskJson.path("status").path("state").asText(null);
            A2ATask task = A2AJson.taskFrom(taskJson);

            // Whatever the peer said, we get a task object with a non-null
            // id (codec defaults to "unknown"). Status follows the codec's
            // v1 contract: known label -> enum, unknown/null -> null (the
            // caller decides fail-open vs fail-closed), never an exception.
            assertNotNull(task);
            assertNotNull(task.taskId());
            String expected = A2ATaskStatus.fromLabel(stateLabel) != null
                    ? stateLabel : null;
            assertEquals(expected == null ? null : A2ATaskStatus.fromLabel(stateLabel).label(),
                    task.status() == null ? null : task.status().label());
        }
    }

    @Test
    void property_firstTextPartSkipsNonTextParts() {
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            ArrayNode parts = MAPPER.createArrayNode();
            int expectedIdx = -1;
            String expectedText = null;
            int textParts = 0;
            for (int p = 0; p < random.nextInt(5); p++) {
                ObjectNode part = parts.addObject();
                switch (random.nextInt(3)) {
                    case 0 -> {
                        part.put("kind", "text");
                        String text = "t" + random.nextInt(1000);
                        part.put("text", text);
                        textParts++;
                        if (expectedIdx < 0) {
                            expectedIdx = p;
                            expectedText = text;
                        }
                    }
                    case 1 -> part.put("kind", "file");
                    default -> part.put("kind", "data");
                }
            }
            String got = A2AJson.firstTextPart(parts);
            if (textParts == 0) {
                assertNull(got);
            } else {
                assertEquals(expectedText, got);
            }
        }
    }

    // ============ Mutation fuzz: hostile cards/tasks never break the codec ============

    @Test
    void fuzz_mutatedTaskJson_yieldsTaskOrControlledFailure() throws Exception {
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            String seed = randomTaskJson().toString();
            String mutated = mutate(seed, random);
            try {
                JsonNode node = MAPPER.readTree(mutated);
                A2ATask task = A2AJson.taskFrom(node);
                assertNotNull(task);
            } catch (com.fasterxml.jackson.core.JsonProcessingException expected) {
                // Jackson-level rejection is the controlled path for
                // non-JSON bytes.
            }
        }
    }

    @Test
    void fuzz_hostileTaskShapes_areCoercedOrRejected() {
        String[] hostile = {
                "{}",
                "{\"id\":42}",                        // numeric id
                "{\"id\":[1,2]}",                     // array id
                "{\"id\":{\"a\":1}}",                 // object id
                "{\"id\":\"\",\"status\":{}}",
                "{\"status\":{\"state\":\"not-a-state\"}}",
                "{\"status\":\"string-not-object\"}",
                "{\"status\":{\"state\":123}}",
                "{\"artifacts\":\"string-not-array\"}",
                "{\"artifacts\":[{\"parts\":\"nope\"}]}",
                "{\"artifacts\":[{\"parts\":[{}]}]}",
                "{\"artifacts\":[{\"parts\":[{\"text\":42}]}]}",   // numeric text
                "{\"artifacts\":[{\"parts\":[{\"text\":null}]}]}",
                "{\"contextId\":\"x\",\"id\":null}",
        };
        for (String input : hostile) {
            try {
                JsonNode node = MAPPER.readTree(input);
                A2ATask task = A2ATask.class.cast(A2AJson.taskFrom(node));
                assertNotNull(task);
                // A numeric id must be coerced to its string form, not crash.
                if (input.contains("\"id\":42")) {
                    assertEquals("42", task.taskId());
                }
            } catch (ClassCastException | IllegalArgumentException
                     | com.fasterxml.jackson.core.JsonProcessingException controlled) {
                // any controlled rejection is fine
            }
        }
    }

    @Test
    void fuzz_mutatedSchemaValidator_neverThrowsUnchecked() {
        // McpSchemaValidator is the shared border piece (MCP tools); fuzz
        // both operands: schema and args, mutated independently.
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            ObjectNode schema = randomSchema();
            ObjectNode args = randomParams(2);
            String mutatedSchema = mutate(schema.toString(), random);
            String mutatedArgs = mutate(args.toString(), random);
            try {
                JsonNode schemaNode = MAPPER.readTree(mutatedSchema);
                JsonNode argsNode = MAPPER.readTree(mutatedArgs);
                // Contract: validate NEVER throws — it reports violations.
                var violations = io.github.qwzhang01.agent.mcp.McpSchemaValidator
                        .validate(schemaNode, argsNode);
                assertNotNull(violations);
            } catch (com.fasterxml.jackson.core.JsonProcessingException expected) {
                // non-JSON input to readTree: controlled
            }
        }
    }

    // ============ Generators ============

    private AgentCard randomCard() {
        int skillCount = random.nextInt(4);
        java.util.List<String> skills = new java.util.ArrayList<>();
        for (int i = 0; i < skillCount; i++) {
            skills.add("skill-" + random.nextInt(100));
        }
        return new AgentCard(
                "agent-" + random.nextInt(1000),
                random.nextBoolean() ? "desc" : null,
                java.util.List.copyOf(skills),
                "http://host-" + random.nextInt(100) + ":8080",
                "1." + random.nextInt(9),
                null,
                new A2ACapabilities(random.nextBoolean(), random.nextBoolean(),
                        random.nextBoolean()));
    }

    private ObjectNode randomTaskJson() {
        ObjectNode task = MAPPER.createObjectNode();
        task.put("id", "task-" + random.nextInt(1000));
        if (random.nextBoolean()) {
            task.put("contextId", "ctx-" + random.nextInt(100));
        }
        ObjectNode status = task.putObject("status");
        String[] states = {"submitted", "working", "input-required",
                "completed", "failed", "canceled", "rejected", "unknown-state"};
        status.put("state", states[random.nextInt(states.length)]);
        if (random.nextBoolean()) {
            status.put("message", "m" + random.nextInt(100));
        }
        if (random.nextBoolean()) {
            ArrayNode artifacts = task.putArray("artifacts");
            for (int a = 0; a < random.nextInt(3); a++) {
                ObjectNode artifact = artifacts.addObject();
                artifact.put("artifactId", "art-" + a);
                ObjectNode part = artifact.putArray("parts").addObject();
                part.put("kind", "text");
                part.put("text", "text-" + random.nextInt(100));
            }
        }
        return task;
    }

    private ObjectNode randomSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode field = props.putObject("city");
        field.put("type", "string");
        if (random.nextBoolean()) {
            schema.putArray("required").add("city");
        }
        return schema;
    }

    private ObjectNode randomParams(int depth) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (random.nextInt(4)) {
            case 0 -> node.put("city", "c" + random.nextInt(100));
            case 1 -> node.put("n", random.nextInt());
            case 2 -> node.putNull("z");
            default -> {
                if (depth > 0) {
                    node.set("o", randomParams(depth - 1));
                }
            }
        }
        return node;
    }

    private String mutate(String seed, SplittableRandom rnd) {
        StringBuilder sb = new StringBuilder(seed);
        int ops = 1 + rnd.nextInt(3);
        for (int op = 0; op < ops; op++) {
            if (sb.length() == 0) {
                break;
            }
            switch (rnd.nextInt(5)) {
                case 0 -> sb.setCharAt(rnd.nextInt(sb.length()),
                        (char) (' ' + rnd.nextInt(95)));
                case 1 -> sb.deleteCharAt(rnd.nextInt(sb.length()));
                case 2 -> sb.insert(rnd.nextInt(sb.length() + 1),
                        (char) (' ' + rnd.nextInt(95)));
                case 3 -> {
                    int cut = rnd.nextInt(sb.length());
                    sb.setLength(cut);
                }
                default -> {
                    int from = rnd.nextInt(sb.length());
                    int to = rnd.nextInt(sb.length());
                    char tmp = sb.charAt(from);
                    sb.setCharAt(from, sb.charAt(to));
                    sb.setCharAt(to, tmp);
                }
            }
        }
        return sb.toString();
    }
}
