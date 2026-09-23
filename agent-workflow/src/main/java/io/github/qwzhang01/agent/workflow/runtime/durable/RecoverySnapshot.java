package io.github.qwzhang01.agent.workflow.runtime.durable;

import io.github.qwzhang01.agent.workflow.StepRecord;

import java.util.List;
import java.util.Optional;

/**
 * Pre-recovery human diagnostics (, harness roadmap): before a
 * run resumes after a crash, an operator (or the resume API itself) can
 * ask "where exactly was this run, what was its last event, what failed,
 * and what side effects already landed?" — and get a single answer
 * instead of grepping checkpoint files.
 *
 * @param runId the run in question
 * @param status current RunState name
 * @param cursor the node the run would re-execute on resume
 * @param lastEventSeq last applied event/checkpoint sequence
 * @param lastTrace trailing step records (bounded)
 * @param lastError last recorded failure reason (null = none)
 * @param completedEffects side effects already recorded in the ledger
 * @param version the row version a resume must carry
 */
public record RecoverySnapshot(
        String runId,
        String status,
        String cursor,
        long lastEventSeq,
        List<StepRecord> lastTrace,
        String lastError,
        List<SideEffectLedger.Effect> completedEffects,
        long version) {

    /**
     * Assemble the snapshot from the durable stores. The RunStore row is
     * the spine; the ledger contributes the completed-effects section.
     */
    public static RecoverySnapshot of(RunRecord record, SideEffectLedger ledger) {
        List<SideEffectLedger.Effect> effects =
                ledger == null ? List.of() : ledger.effectsForRun(record.runId());
        String lastError = record.errorMessage();
        StepRecord lastFailed = null;
        for (StepRecord r : record.lastTrace()) {
            if (r.status() == StepRecord.Status.FAILED) {
                lastFailed = r;
            }
        }
        if (lastError == null && lastFailed != null) {
            lastError = lastFailed.summary();
        }
        return new RecoverySnapshot(record.runId(), record.status(), record.cursor(),
                record.lastEventSeq(), record.lastTrace(), lastError, effects, record.version());
    }

    /** Convenience for tests and operators: the resume cursor or "START". */
    public String cursorOrStart() {
        return cursor == null ? "START" : cursor;
    }
}
