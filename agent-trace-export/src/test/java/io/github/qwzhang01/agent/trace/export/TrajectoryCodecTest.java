package io.github.qwzhang01.agent.trace.export;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.trace.testsupport.TrajectoryFixture;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The v1 contract lock (M14.2 verification): golden field-name snapshot in
 * snake_case, envelope discipline, lossless round-trip, double round-trip
 * stability (omitted-null fields must re-omit identically).
 */
class TrajectoryCodecTest {

    private final TrajectoryCodec codec = new TrajectoryCodec();

    @Test
    void envelopeCarriesVersionAndKind() {
        Trajectory scored = TrajectoryFixture.withReward(TrajectoryFixture.successful("run-1"), 1.0);
        JsonNode node = codec.toJson(scored);
        assertEquals("v1", node.get("api_version").asText());
        assertEquals("Trajectory", node.get("kind").asText());
        assertEquals("DONE", node.get("done_reason").asText());
        assertEquals(1.0, node.get("reward").asDouble(), 1e-9);
        assertEquals("test", node.get("reward_source").asText());
    }

    @Test
    void unscoredTrajectoryOmitsRewardFields() {
        JsonNode node = codec.toJson(TrajectoryFixture.successful("run-1"));
        assertFalse(node.has("reward"), "unscored trajectory must omit reward");
        assertFalse(node.has("reward_source"));
    }

    /**
     * Golden field-name snapshot: every key that can appear anywhere in the
     * contract, in snake_case. A rename here is an api_version bump (D2/D8)
     * - this test is the CI tripwire.
     */
    @Test
    void goldenFieldNameSnapshot() {
        Set<String> expected = new TreeSet<>(List.of(
                // top level
                "api_version", "kind", "trajectory_id", "run_id", "metadata", "status",
                "done_reason", "reward", "reward_source", "messages", "steps",
                // metadata
                "agent_name", "prompt_sha256", "tools", "max_steps", "started_at",
                "finished_at", "duration_ms", "token_usage", "last_error", "custom",
                // token usage
                "prompt_tokens", "completion_tokens", "total_tokens", "cached_tokens",
                // message + tool call
                "role", "content", "tool_calls", "tool_call_id", "name", "id", "arguments",
                // step + action + observation
                "index", "state", "action", "observations", "done", "finish_reason",
                "usage", "duration_ms", "success"));
        // union of shapes covers the whole contract (scored adds reward/reward_source,
        // failure adds last_error); no single shape may introduce an unknown field
        Trajectory scored = TrajectoryFixture.withReward(TrajectoryFixture.successful("run-1"), 1.0);
        Set<String> scoredFields = collectFieldNames(codec.toJson(scored));
        Set<String> failedFields = collectFieldNames(codec.toJson(TrajectoryFixture.failed("run-err")));
        Set<String> union = new TreeSet<>(scoredFields);
        union.addAll(failedFields);
        assertEquals(expected, union);
        assertTrue(expected.containsAll(scoredFields), "scored shape introduced unknown fields");
        assertTrue(expected.containsAll(failedFields), "failure shape introduced unknown fields");
    }

    @Test
    void roundTripIsLosslessForTextTrajectories() {
        Trajectory original = TrajectoryFixture.successful("run-1");
        assertEquals(original, codec.fromJson(codec.toJson(original)));
        Trajectory failure = TrajectoryFixture.failed("run-2");
        assertEquals(failure, codec.fromJson(codec.toJson(failure)));
    }

    @Test
    void doubleRoundTripTreeIsStable() {
        Trajectory original = TrajectoryFixture.successful("run-1");
        JsonNode once = codec.toJson(original);
        JsonNode twice = codec.toJson(codec.fromJson(once));
        assertEquals(once, twice, "re-serializing a loaded trajectory must reproduce the same tree");
    }

    @Test
    void observationsAreVerbatimIncludingErrorText() {
        JsonNode node = codec.toJson(TrajectoryFixture.successful("run-1"));
        String observation = node.get("steps").get(1).get("observations").get(0).get("content").asText();
        assertEquals("[ERROR] boom", observation);
    }

    // ============ Stage 5.3: export redaction ============

    @Test
    void maskedConstructor_redactsAllTextSurfaces() {
        io.github.qwzhang01.agent.core.redact.SecretMasker masker =
                io.github.qwzhang01.agent.core.redact.SecretMasker.withDefaults();
        TrajectoryCodec maskedCodec = new TrajectoryCodec(masker);

        io.github.qwzhang01.agent.core.model.ChatMessage secretMsg =
                io.github.qwzhang01.agent.core.model.ChatMessage.user(
                        "my api key is sk-abcdefghijklmnopqrst ok?");
        Trajectory t = TrajectoryFixture.successful("run-secret");
        List<io.github.qwzhang01.agent.core.model.ChatMessage> messagesWithSecret =
                new java.util.ArrayList<>(t.messages());
        messagesWithSecret.add(secretMsg);
        Trajectory withSecret = new Trajectory(t.trajectoryId(), t.runId(), t.metadata(),
                t.status(), t.steps(), messagesWithSecret, t.reward(), t.rewardSource());

        JsonNode node = maskedCodec.toJson(withSecret);
        String serialized = node.toString();
        assertFalse(serialized.contains("sk-abcdefghijklmnopqrst"),
                "exported trajectory must not contain the raw secret");
        assertTrue(serialized.contains("[REDACTED:api-key]"),
                "masked placeholder must appear instead");
    }

    @Test
    void legacyConstructor_byteIdenticalToPreStage5() {
        // messagesToJson is public: masker=null must keep legacy passthrough
        List<io.github.qwzhang01.agent.core.model.ChatMessage> msgs = List.of(
                io.github.qwzhang01.agent.core.model.ChatMessage.user(
                        "token sk-abcdefghijklmnopqrst inside"));
        String legacy = new TrajectoryCodec().messagesToJson(msgs).toString();
        String masked = new TrajectoryCodec(
                io.github.qwzhang01.agent.core.redact.SecretMasker.withDefaults())
                .messagesToJson(msgs).toString();
        assertNotEquals(legacy, masked);
        assertTrue(legacy.contains("sk-abcdefghijklmnopqrst"));
        assertFalse(masked.contains("sk-abcdefghijklmnopqrst"));
    }

    @Test
    void unsupportedVersionRejected() {
        var node = codec.toJsonNode("{\"api_version\":\"v9\",\"kind\":\"Trajectory\"}");
        assertThrows(IllegalArgumentException.class, () -> codec.fromJson(node));
    }

    private static Set<String> collectFieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                names.add(e.getKey());
                // "custom" and "arguments" are free-form DATA containers, not schema -
                // their keys belong to the payload, never to the contract
                if (!e.getKey().equals("custom") && !e.getKey().equals("arguments")) {
                    collect(e.getValue(), names);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, names));
        }
    }
}
