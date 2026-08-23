package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

/**
 * One run's recording session (Stage 14 M14.1).
 * <p>
 * Lifecycle: {@code open(runId)} on {@link TrajectoryRecorder} creates and
 * thread-binds a session; boundary decorators feed events into it while the
 * loop runs; {@link #finish} assembles the {@link Trajectory} exactly once.
 * <p>
 * Thread model (v1 honest boundary): a session is bound to the thread that
 * opened it. The ReAct loop calls model and tools synchronously on one thread,
 * so boundary capture is thread-confined by construction. This is why the
 * recording decorators must be the OUTERMOST layer (see architecture note §2):
 * inner decorators (e.g. timeout via CompletableFuture) may hop threads.
 */
public interface RunSession extends AutoCloseable {

    /**
     * Attach the agent's static config for metadata enrichment (agent name,
     * prompt fingerprint, tool list, max steps). Optional but recommended;
     * callable at most once, before finish.
     */
    void attach(AgentConfig config);

    /**
     * Assemble and return the trajectory. Exactly once. Unbinds the session
     * from its recorder thread.
     *
     * @param status    terminal loop status (from AgentState.getStatus())
     * @param lastError terminal error text (from AgentState.getLastError(), may be null)
     */
    Trajectory finish(AgentState.Status status, String lastError);

    /**
     * Safety net: if never finished explicitly, assemble with ERROR
     * ("session closed without explicit finish") - a lost trajectory is worse
     * than an honestly-labeled error one. Idempotent.
     */
    @Override
    void close();
}
