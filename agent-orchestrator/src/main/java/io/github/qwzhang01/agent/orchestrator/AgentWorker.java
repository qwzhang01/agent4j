package io.github.qwzhang01.agent.orchestrator;

import io.github.qwzhang01.agent.mcp.a2a.AgentCard;

/**
 * Unified worker abstraction for multi-agent orchestration (Stage 11 M11.1, D1).
 * <p>
 * THE central design decision of Stage 11: the supervisor, result aggregation,
 * failure policies and skill routing see ONE interface -- they do not know (and
 * do not need to know) whether the worker is:
 * <ul>
 *   <li>an in-process agent (same JVM, plain method call, high trust), or</li>
 *   <li>an external agent (A2A protocol delegation, low trust).</li>
 * </ul>
 * Adding a new kind of collaborator (e.g. a gRPC agent) = adding one
 * implementation; the orchestration layer stays untouched. Third payoff of the
 * decorator philosophy (Stage 9: GovernedToolExecutor, Stage 10: ManagedMcpClient).
 * <p>
 * Contract of {@link #execute}:
 * <ul>
 *   <li>NEVER throws -- failures are reported as {@code WorkerResult.success=false}.
 *       This is the foundation of failure isolation (D4): a crashed worker must
 *       not blow up the supervisor's thread pool.</li>
 *   <li>Executes ONCE per call -- retry/timeout/cancel are the supervisor's
 *       policies (M11.3), not worker logic.</li>
 * </ul>
 */
public interface AgentWorker {

    /**
     * The worker's unique name (used for task routing).
     */
    String name();

    /**
     * The worker's capability declaration -- what it can do, how to reach it.
     * Internal workers fill this too (aligned with external A2A agents), so
     * skill-based routing and (v2) LLM-driven dispatch treat both alike.
     */
    AgentCard card();

    /**
     * Execute a task, once. Never throws.
     *
     * @param task the self-contained unit of work
     * @return success or failure as data; never null, never an exception
     */
    WorkerResult execute(WorkerTask task);
}
