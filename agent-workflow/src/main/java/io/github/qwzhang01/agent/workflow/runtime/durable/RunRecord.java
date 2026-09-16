package io.github.qwzhang01.agent.workflow.runtime.durable;

import io.github.qwzhang01.agent.workflow.StepRecord;

import java.util.List;
import java.util.Objects;

/**
 * Durable row for one workflow Run (Stage 3.1, harness roadmap).
 * <p>
 * The RunStore is the <b>source of truth</b> for run metadata — not the JVM
 * active map. A restarted process reconstructs its recovery candidates from
 * these rows. The {@code version} column is the optimistic lock every state
 * transition must carry; {@code lastEventSeq} anchors the run row to its
 * event/checkpoint position so a resume never re-applies consumed history.
 *
 * @param runId             unique run id
 * @param workflowName      Workflow.name() at start time
 * @param workflowVersion   Workflow.version() at start time ("" = unversioned legacy)
 * @param workflowHash      Workflow.fingerprint() at start time ("" = legacy)
 * @param status            RunState name
 * @param cursor            next node to execute (null = none / terminal)
 * @param stepsExecuted     steps consumed so far (maxSteps across resume)
 * @param lastEventSeq      last applied event/checkpoint sequence (0 = none)
 * @param checkpointId      last durable checkpoint id (null = none yet)
 * @param errorMessage      failure reason (null unless FAILED)
 * @param createdAt         epoch ms
 * @param updatedAt         epoch ms of last transition
 * @param version           optimistic lock, incremented on every update
 * @param lastTrace         trailing StepRecords for diagnostics (bounded)
 */
public record RunRecord(
        String runId,
        String workflowName,
        String workflowVersion,
        String workflowHash,
        String status,
        String cursor,
        int stepsExecuted,
        long lastEventSeq,
        String checkpointId,
        String errorMessage,
        long createdAt,
        long updatedAt,
        long version,
        List<StepRecord> lastTrace) {

    public RunRecord {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        lastTrace = lastTrace == null ? List.of() : List.copyOf(lastTrace);
    }

    /** States a restart sweep considers recovery candidates. */
    public boolean isRecoveryCandidate() {
        return "RUNNING".equals(status) || "PAUSED".equals(status)
                || "WAITING_APPROVAL".equals(status);
    }
}
