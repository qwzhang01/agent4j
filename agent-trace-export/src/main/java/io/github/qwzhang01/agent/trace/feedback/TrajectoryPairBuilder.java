package io.github.qwzhang01.agent.trace.feedback;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates and supports same-prompt pairing (Stage 14 D6).
 * <p>
 * "Prompt" is the shared prefix of both logical conversations up to and
 * including the FIRST user message (system prompt + user task). Two
 * rollouts of the same prompt can be preference-compared; trajectories of
 * different prompts cannot - comparing apples to oranges has no preference
 * semantics, so a mismatched prefix fails fast.
 */
public final class TrajectoryPairBuilder {

    private TrajectoryPairBuilder() {
    }

    /** The prompt prefix: messages up to and including the first USER message. */
    public static List<ChatMessage> promptPrefix(Trajectory trajectory) {
        List<ChatMessage> prefix = new ArrayList<>();
        for (ChatMessage message : trajectory.messages()) {
            prefix.add(message);
            if (message.role() == ChatRole.USER) {
                break;
            }
        }
        return prefix;
    }

    /** Everything AFTER the prompt prefix - the rollout's actual response. */
    public static List<ChatMessage> responseSuffix(Trajectory trajectory) {
        int prefixSize = promptPrefix(trajectory).size();
        return new ArrayList<>(trajectory.messages().subList(
                prefixSize, trajectory.messages().size()));
    }

    /** Fail fast unless both trajectories share the exact same prompt prefix. */
    public static void requireSharedPrompt(Trajectory a, Trajectory b) {
        List<ChatMessage> pa = promptPrefix(a);
        List<ChatMessage> pb = promptPrefix(b);
        if (!pa.equals(pb)) {
            throw new IllegalArgumentException(
                    "preference pairing needs the SAME prompt prefix: A starts with " + summarize(pa)
                            + ", B starts with " + summarize(pb));
        }
    }

    /**
     * Build a finished pair (validation included): preferred refers to the
     * trajectory the human judged better.
     */
    public static PreferencePair pair(Trajectory a, Trajectory b, String preferred, String annotator) {
        requireSharedPrompt(a, b);
        return new PreferencePair(null, a.trajectoryId(), b.trajectoryId(), preferred, annotator, null);
    }

    private static String summarize(List<ChatMessage> prefix) {
        var roles = prefix.stream().map(m -> m.role().name()).toList();
        var firstUser = prefix.stream()
                .filter(m -> m.role() == ChatRole.USER)
                .map(ChatMessage::content)
                .findFirst().orElse("<no user msg>");
        return roles + " user=" + abbreviate(firstUser);
    }

    private static String abbreviate(String text) {
        return text.length() <= 24 ? text : text.substring(0, 24) + "...";
    }
}
