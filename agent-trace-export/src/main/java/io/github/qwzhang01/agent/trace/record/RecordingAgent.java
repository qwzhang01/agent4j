package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.Objects;

/**
 * Agent decorator (Stage 14 M14.1, sugar): opens/finishes the recording
 * session around each run and attaches the delegate's config for metadata,
 * so the assembling layer writes one line instead of try/finally plumbing.
 * <p>
 * The delegate should support the {@link Agent#run(ChatMessage, AgentState)}
 * entry (SimpleAgent does). If a session is ALREADY open on this thread
 * (nested recording, e.g. an AgentNode running an inner wired agent), this
 * wrapper stands down and delegates untouched - v1 records one agent per
 * thread; the already-installed boundary decorators decide what gets captured.
 */
public final class RecordingAgent implements Agent {

    private final Agent delegate;
    private final TrajectoryRecorder recorder;

    private RecordingAgent(Agent delegate, TrajectoryRecorder recorder) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    public static RecordingAgent wrap(Agent delegate, TrajectoryRecorder recorder) {
        return new RecordingAgent(delegate, recorder);
    }

    @Override
    public String run(String userInput) {
        return run(ChatMessage.user(userInput), new AgentState());
    }

    @Override
    public String run(String userInput, AgentState state) {
        return run(ChatMessage.user(userInput), state);
    }

    @Override
    public String run(ChatMessage userMessage) {
        return run(userMessage, new AgentState());
    }

    @Override
    public String run(ChatMessage userMessage, AgentState state) {
        if (recorder.currentSession() != null) {
            return delegate.run(userMessage, state);
        }
        RunSession session = recorder.open(null);
        session.attach(delegate.getConfig());
        try {
            return delegate.run(userMessage, state);
        } finally {
            session.finish(state.getStatus(), state.getLastError());
        }
    }

    @Override
    public AgentConfig getConfig() {
        return delegate.getConfig();
    }
}
