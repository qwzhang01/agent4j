package io.github.qwzhang01.agent.workflow.plan;

import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Replay/Time Travel (Stage 9): reconstruct an agent's conversation from a
 * recorded event history WITHOUT re-executing real side effects.
 * <p>
 * What it does: folds a {@link AgentEvent} stream (the loop's own emitted
 * facts: ModelCallStarted/Finished, ToolStarted/ToolFinished, ContentDelta,
 * Done) back into an {@link io.github.qwzhang01.agent.core.agent.AgentState}
 * that the NEXT model call treats as its own history. Tools are replayed
 * from their RECORDED results ({@link ToolFinished}) — no tool executes;
 * side effects already landed in the original run stay landed, and nothing
 * new lands.
 * <p>
 * What it does NOT do: it does not re-run the model. The reconstructed
 * state ends right before the next model call would happen; a replay host
 * resumes the agent (e.g. {@code agent.run(input, replayedState)}) and the
 * loop continues from the historical prefix. Time travel = re-entry at a
 * chosen point, with the world's real state already consistent with it.
 * <p>
 * Honest limits (v1): events carry no token usage, so the reconstructed
 * state cannot re-derive cost; the fold trusts the event stream's order;
 * divergent histories (a Done that never came, interleaved deltas after a
 * Done) are folded best-effort and flagged via {@link #anomalies()}.
 */
public final class EventReplayer {

    /** The fold result: the reconstructed state plus anomalies found. */
    public record Replay(AgentState state, List<String> anomalies) {
    }

    /**
     * Fold an event history into a fresh state.
     *
     * @param events the recorded stream (in emission order)
     * @param maxSteps the step ceiling to stamp on the rebuilt state
     *                 (the original run's budget; replay may not exceed it)
     */
    public Replay replay(List<AgentEvent> events, int maxSteps) {
        List<String> anomalies = new ArrayList<>();
        AgentState state = new AgentState();
        boolean done = false;
        int step = 0;
        String pendingAssistantText = null;
        List<io.github.qwzhang01.agent.core.model.ToolCall> pendingCalls = null;

        for (AgentEvent event : events) {
            if (done) {
                anomalies.add("event after Done: " + event.getClass().getSimpleName());
                continue;
            }
            if (event instanceof AgentEvent.ModelCallStarted mcs) {
                flushAssistant(state, pendingAssistantText);
                pendingAssistantText = null;
                pendingCalls = null;
                step = Math.max(step, mcs.step());
            } else if (event instanceof AgentEvent.ToolStarted ts) {
                // ToolStarted is a live-UI fact; the tool result lands via
                // ToolFinished. Pending assistant text (the text streamed
                // before the tool batch was declared) is KEPT — the real
                // loop writes text and tool calls into one assistant turn.
                if (pendingCalls == null) {
                    pendingCalls = new ArrayList<>();
                }
                pendingCalls.add(ts.toolCall());
            } else if (event instanceof AgentEvent.ToolFinished tf) {
                // Recorded result replay: the tool does NOT execute. The
                // toolResult message is written from history.
                state.addMessage(ChatMessage.tool(tf.toolCallId(), tf.toolName(), tf.result()));
            } else if (event instanceof AgentEvent.ContentDelta cd) {
                pendingAssistantText = (pendingAssistantText == null ? "" : pendingAssistantText)
                        + cd.delta();
            } else if (event instanceof AgentEvent.Done d) {
                done = true;
                // Flush any pending assistant text that streamed via deltas
                // (text and tool results may share one assistant turn).
                flushAssistant(state, pendingAssistantText);
                pendingAssistantText = null;
                state.setStatus(AgentState.Status.DONE);
                step = Math.max(step, d.state().getCurrentStep());
            } else if (event instanceof AgentEvent.Error e) {
                anomalies.add("error event in history: " + e.message());
            }
            // Handoff / RetryStarted / Reflection* / TurnTrace are
            // observability facts, not conversation content: skipped.
        }

        if (!done) {
            anomalies.add("history ended without Done (partial replay)");
            flushAssistant(state, pendingAssistantText);
            pendingAssistantText = null;
            state.setStatus(AgentState.Status.IDLE);
        }
        // Stamp the reconstructed step count so a resumed loop budgets
        // from where history actually left off.
        while (state.getCurrentStep() < step) {
            state.incrementStep();
        }
        return new Replay(state, anomalies);
    }

    /**
     * Interactive time travel (Stage 9 gap closure): fold only the FIRST
     * {@code uptoIndex} events (exclusive bound: index {@code uptoIndex}
     * itself is NOT folded) and return the world at that boundary — the
     * reconstructed state, whether the original run had already reached
     * {@code Done} within the prefix, and the anomalies seen so far.
     * <p>
     * Semantics a stepping host needs:
     * <ul>
     *   <li>stepping BEFORE the Done lands yields a mid-run world: state
     *       IDLE, partial history, no "partial replay" anomaly (the cut
     *       is the host's choice, not a broken recording)</li>
     *   <li>stepping PAST the end is a host bug, not a partial world:
     *       {@code IndexOutOfBoundsException}, fail-loud</li>
     *   <li>{@code doneWithinPrefix} tells the host whether the original
     *       run's terminal fact is inside the window — a host may step
     *       back from after-Done to before-Done and get a world where the
     *       run is still open</li>
     * </ul>
     * The fold logic is exactly {@link #replay(List, int)}'s — the same
     * recording is re-read at any depth without re-executing anything.
     *
     * @param events   the recorded stream (in emission order)
     * @param maxSteps the step ceiling stamped on the rebuilt state
     * @param uptoIndex exclusive bound on how many events to fold
     *                  (0 = empty world, events.size() = full replay)
     */
    public PrefixReplay replayPrefix(List<AgentEvent> events, int maxSteps, int uptoIndex) {
        if (uptoIndex < 0 || uptoIndex > events.size()) {
            throw new IndexOutOfBoundsException(
                    "prefix bound " + uptoIndex + " outside history of "
                            + events.size() + " events - time travel cannot step past the recording");
        }
        Replay replayed = replay(events.subList(0, uptoIndex), maxSteps);
        // replay() only stamps DONE when a Done event was actually folded,
        // so the state's status IS the done-within-prefix answer; the
        // subList cut cannot hide a Done that was already inside.
        boolean doneWithinPrefix = replayed.state().getStatus() == AgentState.Status.DONE;
        List<String> anomalies = new ArrayList<>(replayed.anomalies());
        if (uptoIndex < events.size() && !anomalies.isEmpty()) {
            // A prefix cut before Done always yields replay()'s "history
            // ended without Done" anomaly — correct for a broken
            // recording, wrong for a deliberate prefix. Drop it.
            anomalies.removeIf(a -> a.startsWith("history ended without Done"));
        }
        return new PrefixReplay(replayed.state(), doneWithinPrefix, anomalies);
    }

    /** The world at a time-travel boundary. */
    public record PrefixReplay(AgentState state, boolean doneWithinPrefix,
                               List<String> anomalies) {
    }

    /** Write accumulated assistant text as one history message (if any). */
    private static void flushAssistant(AgentState state, String text) {
        if (text != null && !text.isBlank()) {
            state.addMessage(ChatMessage.assistant(text));
        }
    }

    /**
     * Extract the recorded final answer from a history (the last assistant
     * message), or null when the history has none.
     */
    public static String finalAnswerOf(AgentState state) {
        String last = null;
        for (ChatMessage m : state.getMessages()) {
            if (m.role() == ChatRole.ASSISTANT && m.content() != null) {
                last = m.content();
            }
        }
        return last;
    }
}
