package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.StepAction;
import io.github.qwzhang01.agent.trace.trajectory.ToolObservation;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryMetadata;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryStep;
import io.github.qwzhang01.agent.trace.trajectory.TrajectorySteps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Package-private implementation of {@link RunSession}. Only
 * {@link TrajectoryRecorder} creates sessions; the boundary decorators
 * (same package) feed events through the package-visible methods.
 */
final class RecordingSession implements RunSession {

    private final TrajectoryRecorder recorder;
    private final String trajectoryId;
    private final String runId;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();

    // Config snapshot taken at attach() time (registry may change later - snapshot now)
    private String agentName;
    private String promptSha256;
    private List<String> toolNames;
    private Integer maxSteps;
    private boolean attached;

    private final List<TrajectoryStep> steps = new ArrayList<>();

    // Pending step under assembly: set by onModelCall, observations appended,
    // flushed when the next model call arrives or at finish()
    private List<ChatMessage> pendingState;
    private StepAction pendingAction;
    private List<ToolObservation> pendingObservations;

    private String modelError;
    private boolean finished;

    RecordingSession(TrajectoryRecorder recorder, String runId) {
        this.recorder = recorder;
        this.runId = runId;
        this.trajectoryId = "traj-" + UUID.randomUUID();
    }

    // ============ Boundary Events (package-visible, called by decorators) ============

    void onModelCall(ModelRequest request, ModelResponse response, long durationMs) {
        requireNotFinished();
        flushPendingStep(false, null);
        pendingState = List.copyOf(request.messages());
        pendingAction = new StepAction(response.content(), response.toolCalls(),
                response.finishReason(), response.usage(), durationMs);
        pendingObservations = new ArrayList<>();
    }

    void onModelError(ModelRequest request, Throwable error, long durationMs) {
        requireNotFinished();
        flushPendingStep(false, null);
        // The failed call is itself a terminal step: the run ends here
        // (the loop catches the exception and returns ERROR).
        steps.add(new TrajectoryStep(
                steps.size() + 1,
                List.copyOf(request.messages()),
                new StepAction(null, null, "error", null, durationMs),
                List.of(), null, true, DoneReason.ERROR));
        modelError = error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    void onToolCall(ToolCall call, String result, boolean success, long durationMs) {
        requireNotFinished();
        if (pendingObservations == null) {
            // Defensive: tool execution outside a model call on this session
            // (not reachable via ReActAgentLoop) - ignore rather than invent a step.
            return;
        }
        pendingObservations.add(new ToolObservation(call.id(), call.name(), result, success, durationMs));
    }

    // ============ RunSession API ============

    @Override
    public void attach(AgentConfig config) {
        requireNotFinished();
        if (attached) {
            throw new IllegalArgumentException("agent config already attached to this session");
        }
        attached = true;
        this.agentName = config.getName();
        this.promptSha256 = TrajectoryMetadata.sha256Hex(config.getSystemPrompt());
        this.maxSteps = config.getMaxSteps();
        this.toolNames = config.getToolRegistry() == null
                ? List.of()
                : config.getToolRegistry().listTools().stream().map(t -> t.getName()).toList();
    }

    @Override
    public Trajectory finish(AgentState.Status status, String lastError) {
        requireNotFinished();

        DoneReason reason = DoneReason.from(status);
        String error = lastError != null ? lastError : modelError;
        if (reason == null) {
            // Non-terminal status reaching finish: caller bug or aborted run.
            // Label honestly as ERROR instead of recording a fake terminal.
            reason = DoneReason.ERROR;
            if (error == null) {
                error = "run aborted in non-terminal status " + status;
            }
        }
        if (reason == DoneReason.ERROR && error == null) {
            error = "run finished with ERROR (no detail)";
        }
        flushPendingStep(true, reason);
        finished = true;

        AgentState.Status terminalStatus = DoneReason.from(status) == null
                ? AgentState.Status.ERROR   // normalize non-terminals (see above)
                : status;

        var metadata = new TrajectoryMetadata(
                agentName, promptSha256, toolNames, maxSteps,
                startedAt, Instant.now(),
                (System.nanoTime() - startedNanos) / 1_000_000,
                aggregateUsage(), error, Map.of());

        var trajectory = new Trajectory(trajectoryId, runId, metadata, terminalStatus,
                steps, TrajectorySteps.logicalMessages(steps), null, null);
        recorder.onSessionFinished(this, trajectory);
        return trajectory;
    }

    @Override
    public void close() {
        if (!finished) {
            finish(AgentState.Status.ERROR, "session closed without explicit finish");
        }
    }

    // ============ Assembly Helpers ============

    private void flushPendingStep(boolean done, DoneReason reason) {
        if (pendingAction == null) {
            return;
        }
        steps.add(new TrajectoryStep(steps.size() + 1, pendingState, pendingAction,
                pendingObservations, null, done, reason));
        pendingState = null;
        pendingAction = null;
        pendingObservations = null;
    }

    private ModelResponse.TokenUsage aggregateUsage() {
        int prompt = 0;
        int completion = 0;
        int total = 0;
        for (TrajectoryStep step : steps) {
            var usage = step.action() != null ? step.action().usage() : null;
            if (usage != null) {
                prompt += usage.promptTokens();
                completion += usage.completionTokens();
                total += usage.totalTokens();
            }
        }
        return new ModelResponse.TokenUsage(prompt, completion, total);
    }

    private void requireNotFinished() {
        if (finished) {
            throw new IllegalStateException("recording session '" + runId + "' is already finished");
        }
    }
}
