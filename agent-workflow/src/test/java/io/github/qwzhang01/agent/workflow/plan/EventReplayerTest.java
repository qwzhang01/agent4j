package io.github.qwzhang01.agent.workflow.plan;

import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replay/Time Travel contract tests: fold an event history into a
 * state WITHOUT re-executing tools; anomalies flagged, not papered over.
 */
class EventReplayerTest {

    // Fold: plain text run

    @Test
    @DisplayName("text-only history: deltas fold into the final assistant answer")
    void textRunFolds() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ContentDelta("Hello "));
        events.add(new AgentEvent.ContentDelta("world"));
        events.add(new AgentEvent.Done("Hello world", doneState(3)));

        EventReplayer.Replay replay = new EventReplayer().replay(events, 10);

        assertTrue(replay.anomalies().isEmpty());
        assertEquals(AgentState.Status.DONE, replay.state().getStatus());
        String answer = EventReplayer.finalAnswerOf(replay.state());
        assertEquals("Hello world", answer);
        assertEquals(3, replay.state().getCurrentStep());
    }

    // Recorded-result replay: tools never execute

    @Test
    @DisplayName("tool results replay from history: no execution, history reconstructed")
    void toolResultsReplayFromRecordedHistory() {
        ToolCall call = ToolCall.of("c1", "search", "{}");

        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ToolStarted(call));
        events.add(new AgentEvent.ToolFinished("c1", "search", "recorded result: 42 hits"));
        events.add(new AgentEvent.ModelCallStarted("a", "m", 3, 2));
        events.add(new AgentEvent.ContentDelta("42 hits found"));
        events.add(new AgentEvent.Done("42 hits found", doneState(2)));

        EventReplayer.Replay replay = new EventReplayer().replay(events, 10);

        assertTrue(replay.anomalies().isEmpty());
        List<ChatMessage> history = replay.state().getMessages();
        // the tool result message is written from the RECORDED result
        assertEquals(2, history.size());
        assertEquals(ChatRole.TOOL, history.get(0).role());
        assertEquals("recorded result: 42 hits", history.get(0).content());
        assertEquals("c1", history.get(0).toolCallId());
        assertEquals(ChatRole.ASSISTANT, history.get(1).role());
        assertEquals("42 hits found", history.get(1).content());
    }


    @Test
    @DisplayName("history without Done: partial replay flagged, status stays IDLE")
    void missingDoneFlagsAnomaly() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ContentDelta("partial"));

        EventReplayer.Replay replay = new EventReplayer().replay(events, 10);

        assertEquals(1, replay.anomalies().size());
        assertTrue(replay.anomalies().get(0).contains("without Done"));
        assertEquals(AgentState.Status.IDLE, replay.state().getStatus());
        // the partial text still folds: time travel to the mid-run point
        assertEquals("partial", EventReplayer.finalAnswerOf(replay.state()));
    }

    @Test
    @DisplayName("events after Done: each one recorded as an anomaly")
    void eventsAfterDoneFlagged() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.Done("done", doneState(1)));
        events.add(new AgentEvent.ContentDelta("late"));
        events.add(new AgentEvent.ToolFinished("c9", "t", "late tool"));

        EventReplayer.Replay replay = new EventReplayer().replay(events, 10);

        assertEquals(2, replay.anomalies().size());
        assertTrue(replay.anomalies().get(0).contains("after Done"));
    }

    @Test
    @DisplayName("Error event in history: anomaly recorded, fold continues")
    void errorEventFlagged() {
        // A failed attempt (Error) inside the history, then the run still
        // finished with a Done — the shape a retry-then-succeed leaves.
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ContentDelta("bad draft"));
        events.add(new AgentEvent.Error("model provider 500", null));
        events.add(new AgentEvent.ModelCallStarted("a", "m", 2, 2));
        events.add(new AgentEvent.ContentDelta("fallback answer"));
        events.add(new AgentEvent.Done("fallback answer", doneState(2)));

        EventReplayer.Replay replay = new EventReplayer().replay(events, 10);

        assertEquals(1, replay.anomalies().size());
        assertTrue(replay.anomalies().get(0).contains("error event"));
        assertEquals("fallback answer", EventReplayer.finalAnswerOf(replay.state()));
    }

    // Observability facts are skipped, not errors

    @Test
    @DisplayName("Handoff/TurnTrace/Reflection events are skipped as observability facts")
    void observabilityFactsSkipped() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.Handoff("a", "b", "transfer_to_b"));
        events.add(new AgentEvent.RetryStarted("bad draft", 2, 3));
        events.add(new AgentEvent.ContentDelta("after retry"));
        events.add(new AgentEvent.Done("after retry", doneState(1)));

        EventReplayer.Replay replay = new EventReplayer().replay(events, 10);

        assertTrue(replay.anomalies().isEmpty());
        assertEquals("after retry", EventReplayer.finalAnswerOf(replay.state()));
    }

    // finalAnswerOf

    @Test
    @DisplayName("finalAnswerOf: last assistant message, null when none")
    void finalAnswerOfSemantics() {
        AgentState state = new AgentState();
        assertNull(EventReplayer.finalAnswerOf(state));

        state.addMessage(ChatMessage.user("q"));
        assertNull(EventReplayer.finalAnswerOf(state), "user message is not an answer");

        state.addMessage(ChatMessage.assistant("first"));
        state.addMessage(ChatMessage.tool("t1", "tool", "r"));
        state.addMessage(ChatMessage.assistant("final"));
        assertEquals("final", EventReplayer.finalAnswerOf(state));
    }

    // Prefix replay: interactive time travel (Gap 4)

    @Test
    @DisplayName("empty prefix: empty world, IDLE, no anomalies")
    void emptyPrefixIsEmptyWorld() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ContentDelta("hello"));
        events.add(new AgentEvent.Done("hello", doneState(1)));

        EventReplayer.PrefixReplay prefix = new EventReplayer().replayPrefix(events, 10, 0);

        assertEquals(AgentState.Status.IDLE, prefix.state().getStatus());
        assertTrue(prefix.state().getMessages().isEmpty());
        assertFalse(prefix.doneWithinPrefix());
        assertTrue(prefix.anomalies().isEmpty());
    }

    @Test
    @DisplayName("cut before Done: mid-run world, partial history kept, no partial-replay anomaly")
    void cutBeforeDoneYieldsMidRunWorld() {
        ToolCall call = ToolCall.of("c1", "search", "{}");
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ToolStarted(call));
        events.add(new AgentEvent.ToolFinished("c1", "search", "recorded: 42 hits"));
        events.add(new AgentEvent.ModelCallStarted("a", "m", 3, 2));
        events.add(new AgentEvent.ContentDelta("42 hits"));
        events.add(new AgentEvent.Done("42 hits", doneState(2)));

        // cut right before the Done event: the world at "answer half-streamed"
        EventReplayer.PrefixReplay prefix = new EventReplayer().replayPrefix(events, 10, 5);

        assertEquals(AgentState.Status.IDLE, prefix.state().getStatus());
        assertFalse(prefix.doneWithinPrefix());
        assertTrue(prefix.anomalies().isEmpty(), "a deliberate cut is not a broken recording");
        // partial history reconstructed: recorded tool result + streamed text so far
        List<ChatMessage> history = prefix.state().getMessages();
        assertEquals(2, history.size());
        assertEquals(ChatRole.TOOL, history.get(0).role());
        assertEquals("recorded: 42 hits", history.get(0).content());
        assertEquals(ChatRole.ASSISTANT, history.get(1).role());
        assertEquals("42 hits", history.get(1).content());

        // one step later the recording closes: full window equals full replay
        EventReplayer.PrefixReplay closed = new EventReplayer().replayPrefix(events, 10, 6);
        assertEquals(AgentState.Status.DONE, closed.state().getStatus());
        assertTrue(closed.doneWithinPrefix());
        assertTrue(closed.anomalies().isEmpty());
    }

    @Test
    @DisplayName("prefix containing Done: terminal world, doneWithinPrefix=true")
    void prefixContainingDoneIsTerminal() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ContentDelta("hello"));
        events.add(new AgentEvent.Done("hello", doneState(1)));
        events.add(new AgentEvent.ContentDelta("late")); // after-Done fact

        // include the Done: the world where the run is closed
        EventReplayer.PrefixReplay prefix = new EventReplayer().replayPrefix(events, 10, 3);

        assertEquals(AgentState.Status.DONE, prefix.state().getStatus());
        assertTrue(prefix.doneWithinPrefix());
        assertTrue(prefix.anomalies().isEmpty());
        assertEquals("hello", EventReplayer.finalAnswerOf(prefix.state()));

        // one step further: the after-Done delta is now INSIDE the window and flagged
        EventReplayer.PrefixReplay wider = new EventReplayer().replayPrefix(events, 10, 4);
        assertTrue(wider.doneWithinPrefix());
        assertEquals(1, wider.anomalies().size());
        assertTrue(wider.anomalies().get(0).contains("after Done"));
    }

    @Test
    @DisplayName("error event inside prefix: anomaly kept, only the cut anomaly is dropped")
    void errorInsidePrefixIsCounted() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ContentDelta("bad draft"));
        events.add(new AgentEvent.Error("model provider 500", null));
        events.add(new AgentEvent.ModelCallStarted("a", "m", 2, 2));
        events.add(new AgentEvent.ContentDelta("fallback answer"));
        events.add(new AgentEvent.Done("fallback answer", doneState(2)));

        // cut before the retry: the failed attempt IS inside the window
        EventReplayer.PrefixReplay prefix = new EventReplayer().replayPrefix(events, 10, 3);

        assertEquals(AgentState.Status.IDLE, prefix.state().getStatus());
        assertFalse(prefix.doneWithinPrefix());
        assertEquals(1, prefix.anomalies().size());
        assertTrue(prefix.anomalies().get(0).contains("error event"));
        // the failed draft's partial text still folds into history
        assertEquals("bad draft", EventReplayer.finalAnswerOf(prefix.state()));
    }

    @Test
    @DisplayName("full-length prefix of a Done-less recording: partial-replay anomaly KEPT")
    void fullLengthCutOfBrokenRecordingKeepsAnomaly() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.ModelCallStarted("a", "m", 1, 1));
        events.add(new AgentEvent.ContentDelta("partial"));

        // uptoIndex == size is NOT a cut: the whole recording lacks Done, that IS broken
        EventReplayer.PrefixReplay prefix = new EventReplayer().replayPrefix(events, 10, 2);

        assertEquals(AgentState.Status.IDLE, prefix.state().getStatus());
        assertFalse(prefix.doneWithinPrefix());
        assertEquals(1, prefix.anomalies().size());
        assertTrue(prefix.anomalies().get(0).contains("without Done"));
    }

    @Test
    @DisplayName("stepping past the recording: fail-loud IndexOutOfBoundsException")
    void steppingPastRecordingFailsLoud() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.Done("done", doneState(1)));

        EventReplayer replayer = new EventReplayer();
        assertThrows(IndexOutOfBoundsException.class,
                () -> replayer.replayPrefix(events, 10, 2));
        assertThrows(IndexOutOfBoundsException.class,
                () -> replayer.replayPrefix(events, 10, -1));
    }

    private static AgentState doneState(int step) {
        AgentState s = new AgentState();
        while (s.getCurrentStep() < step) {
            s.incrementStep();
        }
        s.setStatus(AgentState.Status.DONE);
        return s;
    }
}
