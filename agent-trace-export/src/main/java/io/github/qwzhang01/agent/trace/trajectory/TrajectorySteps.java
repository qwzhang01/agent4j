package io.github.qwzhang01.agent.trace.trajectory;

import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared step-level invariants of the trajectory model (M14.3).
 * <p>
 * The logical-message reconstruction is the SINGLE algorithm used by both
 * producers and verifiers: {@code RecordingSession} assembles it when a run
 * finishes, and {@code ReplayView} recomputes it to prove a loaded
 * trajectory's two channels (messages vs steps) are self-consistent. One
 * algorithm, two roles - divergence between "how we write" and "how we
 * check" would be a silent-corruption bug farm.
 */
public final class TrajectorySteps {

    private TrajectorySteps() {
    }

    /**
     * Rebuild the logical conversation from steps: step 1's leading state
     * plus every action (as assistant message) and observation (as tool
     * message). Deliberately NOT AgentState.getMessages() - derived purely
     * from what the boundaries recorded (see M14.1 RecordingSession).
     */
    public static List<ChatMessage> logicalMessages(List<TrajectoryStep> steps) {
        List<ChatMessage> messages = new ArrayList<>();
        boolean first = true;
        for (TrajectoryStep step : steps) {
            if (first) {
                messages.addAll(step.state());
                first = false;
            }
            StepAction action = step.action();
            if (action == null) {
                continue;
            }
            if (action.hasToolCalls()) {
                messages.add(ChatMessage.assistantWithTools(action.content(), action.toolCalls()));
            } else if (action.content() != null) {
                messages.add(ChatMessage.assistant(action.content()));
            }
            for (ToolObservation obs : step.observations()) {
                messages.add(ChatMessage.tool(obs.toolCallId(), obs.name(), obs.content()));
            }
        }
        return messages;
    }
}
