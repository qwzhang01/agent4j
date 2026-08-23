package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * ModelClient boundary decorator (Stage 14 D1): captures (request, response)
 * = (State, Action) into the active recording session.
 * <p>
 * Why the ModelClient boundary: {@code ReActAgentLoop.buildRequest} applies
 * the ContextBuilder BEFORE calling the model, so this is the only point
 * where the post-compression messages - the policy's real input - are
 * visible. Recording from AgentState would silently lose that divergence
 * (the test {@code RecordingFidelityTest.compressionFidelity} proves it).
 * <p>
 * Wiring order: this decorator must be the OUTERMOST model layer
 * ({@code Recording(Temperature(Fallback(...)))}) - inner decorators may hop
 * threads (timeout), and v1 sessions are thread-bound.
 * <p>
 * Outside a session (no open run on this thread) it is a transparent
 * passthrough. v1 records the synchronous {@link #chat} path only;
 * {@link #stream} passes through unrecorded (honest boundary, replay of
 * streams is v2).
 */
public final class RecordingModelClient implements ModelClient {

    private final ModelClient delegate;
    private final TrajectoryRecorder recorder;

    private RecordingModelClient(ModelClient delegate, TrajectoryRecorder recorder) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    public static RecordingModelClient wrap(ModelClient delegate, TrajectoryRecorder recorder) {
        return new RecordingModelClient(delegate, recorder);
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        RecordingSession session = recorder.currentSession();
        if (session == null) {
            return delegate.chat(request);
        }
        long start = System.nanoTime();
        try {
            ModelResponse response = delegate.chat(request);
            session.onModelCall(request, response, elapsedMs(start));
            return response;
        } catch (RuntimeException | Error e) {
            session.onModelError(request, e, elapsedMs(start));
            throw e;
        }
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        // v1: streaming is not recorded - see class javadoc
        return delegate.stream(request);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
