package io.github.qwzhang01.agent.trace.feedback;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.trace.export.TrajectoryCodec;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Materializes preference pairs into DPO training format (Stage 14 D6/D8):
 * one JSON line per pair with {prompt, chosen, rejected} message sequences -
 * the direct input shape of DPO trainers.
 * <p>
 * Pairs store references; this exporter RESOLVES them against a trajectory
 * pool. Dangling references fail loud (a training pair pointing at a
 * missing conversation is worse than an exception), and prompt-prefix
 * consistency is re-verified at export time - sidecar data and pool may
 * have evolved independently since annotation.
 * <p>
 * The response suffix CAN be empty (e.g. a model-failure rollout produced
 * no assistant reply): "no response" vs "good response" is a legitimate
 * preference, so empty chosen/rejected arrays are exported as-is.
 */
public final class DpoExporter {

    public static final String FILE_NAME = "preferences.jsonl";

    private final Path file;
    private final TrajectoryCodec codec = new TrajectoryCodec();

    public DpoExporter(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * Materialize and append every pair, resolving references against the pool.
     *
     * @return this file's path
     */
    public Path export(Collection<PreferencePair> pairs, Collection<Trajectory> pool) throws IOException {
        Map<String, Trajectory> byId = new HashMap<>();
        for (Trajectory trajectory : pool) {
            byId.put(trajectory.trajectoryId(), trajectory);
        }
        StringBuilder lines = new StringBuilder();
        for (PreferencePair pair : pairs) {
            lines.append(materialize(pair, byId)).append(System.lineSeparator());
        }
        if (!lines.isEmpty()) {
            Files.writeString(file, lines.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return file;
    }

    private String materialize(PreferencePair pair, Map<String, Trajectory> byId) {
        Trajectory a = byId.get(pair.trajectoryA());
        Trajectory b = byId.get(pair.trajectoryB());
        if (a == null || b == null) {
            throw new IllegalArgumentException("dangling reference in pair " + pair.pairId()
                    + ": A=" + pair.trajectoryA() + (a == null ? " MISSING" : "")
                    + ", B=" + pair.trajectoryB() + (b == null ? " MISSING" : ""));
        }
        TrajectoryPairBuilder.requireSharedPrompt(a, b);

        List<ChatMessage> prompt = TrajectoryPairBuilder.promptPrefix(a);
        List<ChatMessage> chosen = "A".equals(pair.preferred())
                ? TrajectoryPairBuilder.responseSuffix(a)
                : TrajectoryPairBuilder.responseSuffix(b);
        List<ChatMessage> rejected = "A".equals(pair.preferred())
                ? TrajectoryPairBuilder.responseSuffix(b)
                : TrajectoryPairBuilder.responseSuffix(a);

        ObjectNode node = codec.createObjectNode();
        node.put("api_version", TrajectoryCodec.API_VERSION);
        node.put("kind", "PreferencePair");
        node.put("pair_id", pair.pairId());
        node.set("prompt", codec.messagesToJson(prompt));
        node.set("chosen", codec.messagesToJson(chosen));
        node.set("rejected", codec.messagesToJson(rejected));
        ObjectNode metadata = node.putObject("metadata");
        metadata.put("preferred", pair.preferred());
        metadata.put("annotator", pair.annotator());
        metadata.put("traj_a", pair.trajectoryA());
        metadata.put("traj_b", pair.trajectoryB());
        if (pair.createdAt() != null) {
            metadata.put("created_at", pair.createdAt().toString());
        }
        return node.toString();
    }
}
