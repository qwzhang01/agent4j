package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;

import java.util.Objects;

/**
 * ToolExecutor boundary decorator (Stage 14 D1): captures (toolCall, result)
 * = Observation into the active recording session.
 * <p>
 * The result text is recorded VERBATIM: Stage 2 "[ERROR] ..." wrapping and
 * Stage 9 "[DENIED]" governance texts are observations like any other - the
 * model saw them, so the trajectory keeps them (record what the policy saw).
 * <p>
 * {@code success=false} only when the executor itself THREW (a framework-level
 * failure); error-wrapped text results count as normal observations. The
 * exception is recorded and rethrown - this decorator never swallows.
 * <p>
 * Outside a session it is a transparent passthrough.
 */
public final class RecordingToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final TrajectoryRecorder recorder;

    private RecordingToolExecutor(ToolExecutor delegate, TrajectoryRecorder recorder) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    public static RecordingToolExecutor wrap(ToolExecutor delegate, TrajectoryRecorder recorder) {
        return new RecordingToolExecutor(delegate, recorder);
    }

    @Override
    public String execute(ToolCall toolCall) {
        RecordingSession session = recorder.currentSession();
        if (session == null) {
            return delegate.execute(toolCall);
        }
        long start = System.nanoTime();
        try {
            String result = delegate.execute(toolCall);
            session.onToolCall(toolCall, result, true, elapsedMs(start));
            return result;
        } catch (RuntimeException | Error e) {
            session.onToolCall(toolCall,
                    "[EXECUTOR ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    false, elapsedMs(start));
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
