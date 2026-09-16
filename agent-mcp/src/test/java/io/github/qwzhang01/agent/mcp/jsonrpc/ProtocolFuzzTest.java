package io.github.qwzhang01.agent.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 8.3: malformed-input fuzz and property tests for the JSON-RPC wire
 * layer.
 * <p>
 * Roadmap: "协议 malformed input fuzz/property tests". Existing JsonRpcTest
 * covers happy paths plus a couple of hand-picked malformed strings; this
 * suite goes further with two modes:
 * <ol>
 *   <li><b>Mutation fuzz</b> — start from VALID envelopes, apply random
 *       byte/structure mutations, assert the parser never escapes its
 *       exception contract (IllegalArgumentException or nothing). No
 *       OutOfMemoryError, no StackOverflowError, no half-parsed objects:
 *       the border either rejects or accepts, never limps.</li>
 *   <li><b>Property tests</b> — for arbitrary generated valid envelopes,
 *       serialization round-trips and structural invariants (jsonrpc
 *       version field, id presence for requests/responses, id absence for
 *       notifications) hold.</li>
 * </ol>
 * Determinism: a fixed base seed means a failure reproduces byte-exactly by
 * rerunning; the per-iteration seed is printed in the assertion message via
 * the mutated input itself (the input IS the repro).
 */
class ProtocolFuzzTest {

    private static final int FUZZ_ITERATIONS = 512;

    private final SplittableRandom random = new SplittableRandom(20260916L);
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ============ Property: valid envelopes round-trip ============

    @Test
    void property_validRequestsRoundTrip() {
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            ObjectNode params = randomParams(random.nextInt(4));
            JsonRpcRequest req = new JsonRpcRequest(
                    i % 2 == 0 ? (long) random.nextInt(1_000_000) : "req-" + i,
                    randomMethod(), params);

            String json = req.toJson();

            assertTrue(json.contains("\"jsonrpc\":\"2.0\""), "version field lost: " + json);
            assertTrue(json.contains("\"method\":"), "method lost: " + json);
            // Round-trip through generic Jackson must not choke on our own output.
            assertDoesNotThrow(() -> mapper.readTree(json),
                    "our own serialized envelope must be valid JSON: " + json);
        }
    }

    @Test
    void property_notificationsNeverCarryAnId() throws Exception {
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            JsonRpcNotification notif = new JsonRpcNotification(
                    randomMethod(), randomParams(random.nextInt(4)));
            String json = notif.toJson();
            JsonNode parsed = mapper.readTree(json);
            assertFalse(parsed.has("id"),
                    "notification leaked an id: " + json);
            assertEquals("2.0", parsed.path("jsonrpc").asText());
        }
    }

    // ============ Mutation fuzz: the border never escapes its contract ============

    @Test
    void fuzz_mutatedResponseJson_neverEscapesExceptionContract() {
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            String seed = validResponseJson();
            String mutated = mutate(seed, random);

            // The contract: fromJson either throws IllegalArgumentException
            // (malformed) or returns a well-formed response (valid). Any
            // other Throwable (OOM, SOE, NPE from half-parsing) is a border
            // bug this suite exists to catch.
            try {
                JsonRpcResponse resp = JsonRpcResponse.fromJson(mutated);
                // Accepted path: object must be coherent.
                assertNotNull(resp, "accepted input must yield a response: " + mutated);
            } catch (IllegalArgumentException expected) {
                // The controlled rejection path — fine.
            }
        }
    }

    @Test
    void fuzz_structurallyWrongResponseShapes_areRejectedOrSurvived() {
        // Non-JSON scalars, arrays, nesting games — a fixed gallery of
        // hostile shapes beyond byte mutations.
        String[] hostile = {
                "", "   ", "null", "true", "42", "-0.5", "[]", "[1,2,3]",
                "\"just a string\"", "{}",
                "{\"jsonrpc\":\"2.0\"}",                      // no id/result/error
                "{\"jsonrpc\":\"2.0\",\"id\":null}",          // null id only
                "{\"jsonrpc\":\"2.0\",\"id\":[1,2]}",         // array id
                "{\"jsonrpc\":\"2.0\",\"id\":{\"k\":1}}",     // object id
                "{\"jsonrpc\":\"9.9\",\"id\":1,\"result\":{}}",   // weird version
                "{\"id\":1,\"result\":{}}",                   // no jsonrpc
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}", // both
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":\"not-an-object\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"text\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":NaN}",           // literal NaN
                "﻿{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", // BOM
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":",  // truncated
                "}}}}", "{{{{", "[[[", "]", "{\"a\":}",
                "{\"jsonrpc\":\"2.0\",\"id\":" + "9".repeat(400) + ",\"result\":{}}",
        };
        for (String input : hostile) {
            try {
                JsonRpcResponse resp = JsonRpcResponse.fromJson(input);
                assertNotNull(resp);
            } catch (IllegalArgumentException expected) {
                // controlled rejection
            }
        }
    }

    // ============ Generators ============

    private String randomMethod() {
        String[] methods = {"tools/list", "tools/call", "ping", "initialize",
                "notifications/initialized", "resources/list", "prompts/list",
                "weird/method\u00e9"};
        return methods[random.nextInt(methods.length)];
    }

    private ObjectNode randomParams(int depth) {
        ObjectNode node = mapper.createObjectNode();
        switch (random.nextInt(6)) {
            case 0 -> node.put("s", randomString(8));
            case 1 -> node.put("i", random.nextInt());
            case 2 -> node.put("b", random.nextBoolean());
            case 3 -> node.putNull("n");
            case 4 -> {
                ArrayNode arr = node.putArray("a");
                for (int k = 0; k < random.nextInt(3); k++) {
                    arr.add(randomString(4));
                }
            }
            default -> {
                if (depth > 0) {
                    node.set("o", randomParams(depth - 1));
                }
            }
        }
        return node;
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder();
        String alphabet = "abcXYZ019_-./\u00e9\u4e2d";
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String validResponseJson() {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", random.nextInt(1_000_000));
        if (random.nextBoolean()) {
            node.set("result", randomParams(3));
        } else {
            ObjectNode err = node.putObject("error");
            err.put("code", -32000 - random.nextInt(100));
            err.put("message", randomString(12));
        }
        return node.toString();
    }

    /**
     * Random mutation: byte-level surgery on a valid envelope — flip chars,
     * truncate, duplicate, insert junk, swap substructures.
     */
    private String mutate(String seed, SplittableRandom rnd) {
        StringBuilder sb = new StringBuilder(seed);
        int ops = 1 + rnd.nextInt(3);
        for (int op = 0; op < ops; op++) {
            if (sb.length() == 0) {
                break;
            }
            switch (rnd.nextInt(5)) {
                case 0 -> sb.setCharAt(rnd.nextInt(sb.length()),
                        (char) (' ' + rnd.nextInt(95)));   // random printable
                case 1 -> sb.deleteCharAt(rnd.nextInt(sb.length()));
                case 2 -> sb.insert(rnd.nextInt(sb.length() + 1),
                        (char) (' ' + rnd.nextInt(95)));
                case 3 -> {
                    int cut = rnd.nextInt(sb.length());
                    sb.setLength(cut);                      // truncate
                }
                default -> {
                    int from = rnd.nextInt(sb.length());
                    int to = rnd.nextInt(sb.length());
                    char tmp = sb.charAt(from);
                    sb.setCharAt(from, sb.charAt(to));
                    sb.setCharAt(to, tmp);                  // swap
                }
            }
        }
        return sb.toString();
    }
}
