package io.github.qwzhang01.agent.workflow.runtime;

/**
 * Token returned to the caller when a Run pauses.
 * <p>
 * Carries the information needed to resume:
 * - runId: which Run to resume
 * - checkpointId: which checkpoint to load (for crash recovery)
 * - pausedAtNode: which node was paused (for debugging / UI display)
 *
 * @param runId         the paused Run's id
 * @param checkpointId  the checkpoint saved at pause time (null if in-memory only)
 * @param pausedAtNode  the node id where execution paused
 */
public record ResumeToken(String runId, String checkpointId, String pausedAtNode) {
}
