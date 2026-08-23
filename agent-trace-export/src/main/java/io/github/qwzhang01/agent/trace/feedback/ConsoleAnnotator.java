package io.github.qwzhang01.agent.trace.feedback;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.trace.export.TrajectoryCodec;
import io.github.qwzhang01.agent.trace.replay.ReplayView;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Console preference annotator (Stage 14 D6): shows two same-prompt
 * rollouts side by side (step-by-step replay summaries + final answers),
 * reads a/b/skip, appends the verdict to an annotations SIDECAR.
 * <p>
 * The trajectory files themselves are NEVER touched - annotations are
 * separate, append-only data (D5/D6). Input/Output streams are injectable
 * so tests (and the demo example) can drive the interaction without a
 * terminal.
 * <p>
 * v1 interaction covers PAIR preference (the DPO path); single-trajectory
 * ratings go through {@link #rate} programmatically.
 */
public final class ConsoleAnnotator {

    private final Path sidecarFile;
    private final BufferedReader in;
    private final PrintStream out;
    private final TrajectoryCodec codec = new TrajectoryCodec();

    public ConsoleAnnotator(Path sidecarFile) {
        this(sidecarFile, new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    public ConsoleAnnotator(Path sidecarFile, BufferedReader in, PrintStream out) {
        this.sidecarFile = sidecarFile;
        this.in = in;
        this.out = out;
    }

    /**
     * Run one preference decision between two same-prompt rollouts.
     * Mismatched prompt prefixes throw before any interaction.
     *
     * @return the recorded pair, or empty on skip / EOF
     */
    public Optional<PreferencePair> annotate(Trajectory a, Trajectory b) throws IOException {
        TrajectoryPairBuilder.requireSharedPrompt(a, b);
        printRollout('A', a);
        printRollout('B', b);
        out.print("Which rollout is better? [a/b/skip]: ");
        out.flush();
        String line;
        while ((line = in.readLine()) != null) {
            String answer = line.trim().toLowerCase();
            switch (answer) {
                case "a", "b" -> {
                    var pair = TrajectoryPairBuilder.pair(a, b, answer.toUpperCase(), "console");
                    appendSidecar(sidecarJson(pair));
                    return Optional.of(pair);
                }
                case "skip", "" -> {
                    return Optional.empty();
                }
                default -> {
                    out.print("Unrecognized input '" + answer + "'. [a/b/skip]: ");
                    out.flush();
                }
            }
        }
        return Optional.empty();  // EOF treated as skip
    }

    /** Record a single-trajectory rating into the sidecar. */
    public HumanFeedback rate(Trajectory trajectory, int rating, String notes) throws IOException {
        var feedback = new HumanFeedback(trajectory.trajectoryId(), rating, notes, "console", null);
        appendSidecar(sidecarJson(feedback));
        return feedback;
    }

    // ============ rendering ============

    private void printRollout(char label, Trajectory trajectory) {
        out.println("---- rollout " + label + " (" + trajectory.runId() + ") ----");
        out.println("  status=" + trajectory.status() + "  reward=" + trajectory.reward()
                + "  steps=" + trajectory.steps().size());
        ReplayView view = ReplayView.of(trajectory);  // integrity-checked walkthrough
        for (int i = 0; i < view.stepCount(); i++) {
            out.println("  " + view.describeStep(i));
        }
        trajectory.messages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT && m.content() != null)
                .reduce((first, second) -> second)
                .ifPresentOrElse(
                        m -> out.println("  final answer: " + abbreviate(m.content())),
                        () -> out.println("  final answer: <none>"));
    }

    private static String abbreviate(String text) {
        return text.length() <= 72 ? text : text.substring(0, 72) + "...";
    }

    // ============ sidecar ============

    private ObjectNode sidecarJson(PreferencePair pair) {
        ObjectNode node = codec.createObjectNode();
        node.put("api_version", TrajectoryCodec.API_VERSION);
        node.put("kind", "PreferencePair");
        node.put("pair_id", pair.pairId());
        node.put("trajectory_a", pair.trajectoryA());
        node.put("trajectory_b", pair.trajectoryB());
        node.put("preferred", pair.preferred());
        node.put("annotator", pair.annotator());
        if (pair.createdAt() != null) {
            node.put("created_at", pair.createdAt().toString());
        }
        return node;
    }

    private ObjectNode sidecarJson(HumanFeedback feedback) {
        ObjectNode node = codec.createObjectNode();
        node.put("api_version", TrajectoryCodec.API_VERSION);
        node.put("kind", "HumanFeedback");
        node.put("trajectory_id", feedback.trajectoryId());
        node.put("rating", feedback.rating());
        if (feedback.notes() != null && !feedback.notes().isBlank()) {
            node.put("notes", feedback.notes());
        }
        node.put("annotator", feedback.annotator());
        if (feedback.createdAt() != null) {
            node.put("created_at", feedback.createdAt().toString());
        }
        return node;
    }

    private void appendSidecar(ObjectNode node) throws IOException {
        Files.writeString(sidecarFile, node + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
