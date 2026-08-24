package io.github.qwzhang01.agent.coding.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.coding.exec.CommandRunner;
import io.github.qwzhang01.agent.coding.exec.CommandWhitelist;
import io.github.qwzhang01.agent.coding.exec.RunCommandTool;
import io.github.qwzhang01.agent.coding.exec.RunTestsTool;
import io.github.qwzhang01.agent.coding.exec.TestResult;
import io.github.qwzhang01.agent.coding.patch.ApplyResult;
import io.github.qwzhang01.agent.coding.patch.Patch;
import io.github.qwzhang01.agent.coding.patch.PatchStore;
import io.github.qwzhang01.agent.coding.patch.PatchSummarizer;
import io.github.qwzhang01.agent.coding.patch.WriteFileTool;
import io.github.qwzhang01.agent.coding.workspace.ListFilesTool;
import io.github.qwzhang01.agent.coding.workspace.ReadFileTool;
import io.github.qwzhang01.agent.coding.workspace.Workspace;
import io.github.qwzhang01.agent.core.tool.Tool;

import java.util.List;
import java.util.Objects;

/**
 * The governance shell of one coding task (Stage 17 M17.4) - the domain host of the
 * blueprint's 8-step flow: it owns the patch store, the fix-loop budget, the human
 * gates, and the review summary. The Agent assembly (model + systemPrompt + governance
 * chain) is {@code CodingAgentFactory}'s job in M17.5 - this class provides the parts.
 * <p>
 * Fix loop (blueprint D4): the boundary lives here (a veto in {@link #runTestsTool()}
 * - once the failed-run budget is exhausted the tool answers {@code [LIMIT]} and never
 * executes again), the rhythm lives in the model (the failure excerpt naturally pulls
 * it into read -> fix -> re-test). A passing run transitions the staged patch to
 * VALIDATED - passing IS leaving the loop.
 * <p>
 * Human gates: {@link #reviewPatch()} is what the reviewer reads (unified diff +
 * stats); {@link #approveAndApply()} is the only real disk write; {@link #rejectPatch()}
 * and {@link #discardPatch()} both leave the disk untouched. On {@code [LIMIT]} the
 * staged patch is deliberately <b>kept</b> (not auto-discarded) - it is the evidence
 * of what was attempted; discarding is an explicit decision (an honest refinement of
 * blueprint F3: destroy nothing automatically).
 */
public final class CodingSession {

    private final Workspace workspace;
    private final PatchStore patchStore;
    private final CommandWhitelist whitelist;
    private final CommandRunner runner;
    private final List<String> testCommand;
    private final FixLoopPolicy fixLoopPolicy;
    private final PatchSummarizer summarizer;

    private int failedTestRuns = 0;

    private CodingSession(Builder builder) {
        this.workspace = builder.workspace;
        this.patchStore = new PatchStore(workspace);
        this.whitelist = builder.whitelist;
        this.runner = builder.runner;
        this.testCommand = builder.testCommand;
        this.fixLoopPolicy = builder.fixLoopPolicy;
        this.summarizer = builder.summarizer;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ============ Tool factories (consumed by CodingAgentFactory, M17.5) ============

    public ReadFileTool readFileTool() {
        return new ReadFileTool(workspace);
    }

    public ListFilesTool listFilesTool() {
        return new ListFilesTool(workspace);
    }

    public WriteFileTool writeFileTool() {
        return new WriteFileTool(patchStore);
    }

    public RunCommandTool runCommandTool() {
        return new RunCommandTool(whitelist, runner);
    }

    /**
     * The fix-loop-guarded test tool: vetoes execution once the failed-run budget is
     * exhausted, counts failed runs, and validates the staged patch on a pass.
     */
    public Tool runTestsTool() {
        return new LimitedTestsTool();
    }

    // ============ Human gates & review ============

    /** What the reviewer reads before approving: summary + per-file unified diff. */
    public String reviewPatch() {
        Patch patch = patchStore.snapshot().orElseThrow(() -> new IllegalArgumentException(
                "no active patch to review - stage something first (write_file)"));
        return summarizer.summarize(patch);
    }

    /** The human-approved disk write - the only real write point in the whole flow. */
    public ApplyResult approveAndApply() {
        return patchStore.apply();
    }

    /**
     * Human gate rejection: patch -> REJECTED, disk restored. If the patch was
     * materialized (run_tests wrote it to disk so the referee could see it), the
     * baseline is restored first; a drift (human edit on top) aborts the restore but
     * NOT the rejection - human edits outrank machine bookkeeping.
     */
    public Patch rejectPatch() {
        revertMaterializedBestEffort();
        return patchStore.reject();
    }

    /**
     * Explicit throwaway: patch -> DISCARDED, disk restored (same semantics as
     * {@link #rejectPatch()}; NOT auto-invoked on [LIMIT] - the evidence stays).
     */
    public Patch discardPatch() {
        revertMaterializedBestEffort();
        return patchStore.discard();
    }

    private void revertMaterializedBestEffort() {
        patchStore.snapshot().ifPresent(p -> {
            try {
                patchStore.revert();
            } catch (IllegalStateException drift) {
                // human-edited workspace: keep the disk as-is, the human outranks the ledger
            }
        });
    }

    /** The active patch, if any (DRAFT or VALIDATED). */
    public java.util.Optional<Patch> activePatch() {
        return patchStore.snapshot();
    }

    // ============ Fix-loop observation ============

    /** Failed test runs so far - the fix budget's consumed amount. */
    public int failedTestRuns() {
        return failedTestRuns;
    }

    public FixLoopPolicy fixLoopPolicy() {
        return fixLoopPolicy;
    }

    // ============ Internals ============

    private void onVerdict(TestResult verdict) {
        if (!verdict.passed()) {
            failedTestRuns++;
        } else {
            // passing IS leaving the loop: DRAFT patch becomes VALIDATED for human review
            patchStore.snapshot().ifPresent(p -> {
                if (p.status() == Patch.PatchStatus.DRAFT) {
                    patchStore.markValidated();
                }
            });
        }
    }

    /**
     * Tool decorator around {@link RunTestsTool}: the engine-side boundary of the fix
     * loop (blueprint D4). Same Tool contract, so the governance chain and the Agent
     * treat it like any other tool.
     */
    private final class LimitedTestsTool implements Tool {

        private final RunTestsTool delegate =
                new RunTestsTool(testCommand, whitelist, runner);

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription() + " Fix budget: at most "
                    + fixLoopPolicy.maxFixIterations() + " failed run(s) before this tool "
                    + "stops executing.";
        }

        @Override
        public String getParametersSchema() {
            return delegate.getParametersSchema();
        }

        @Override
        public String execute(JsonNode arguments) {
            if (failedTestRuns >= fixLoopPolicy.maxFixIterations()) {
                return "[LIMIT] fix budget exhausted: " + failedTestRuns
                        + " failed test run(s) (policy max "
                        + fixLoopPolicy.maxFixIterations() + "). Stop fixing and report "
                        + "the failure honestly - the staged patch is kept for review.";
            }
            java.util.Optional<String> rejection = delegate.whitelistRejection();
            if (rejection.isPresent()) {
                // the command never ran: no budget consumed
                return rejection.get();
            }
            // blueprint T3's hidden premise: the referee must see the staged changes.
            // Idempotent - a re-run after a fix materializes only what changed.
            patchStore.snapshot().ifPresent(p -> patchStore.materialize());
            TestResult verdict = delegate.run(arguments);
            onVerdict(verdict);
            return verdict.toJson();
        }
    }

    // ============ Builder ============

    public static final class Builder {
        private Workspace workspace;
        private CommandWhitelist whitelist;
        private CommandRunner runner;
        private List<String> testCommand;
        private FixLoopPolicy fixLoopPolicy = FixLoopPolicy.DEFAULT;
        private PatchSummarizer summarizer = new PatchSummarizer();

        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder whitelist(CommandWhitelist whitelist) {
            this.whitelist = whitelist;
            return this;
        }

        public Builder runner(CommandRunner runner) {
            this.runner = runner;
            return this;
        }

        /** The fixed referee, e.g. {@code List.of("mvn", "test")}. Must be whitelisted. */
        public Builder testCommand(List<String> testCommand) {
            this.testCommand = testCommand;
            return this;
        }

        public Builder fixLoopPolicy(FixLoopPolicy fixLoopPolicy) {
            this.fixLoopPolicy = fixLoopPolicy;
            return this;
        }

        public Builder summarizer(PatchSummarizer summarizer) {
            this.summarizer = summarizer;
            return this;
        }

        public CodingSession build() {
            Objects.requireNonNull(workspace, "workspace must not be null");
            Objects.requireNonNull(whitelist, "whitelist must not be null");
            Objects.requireNonNull(runner, "runner must not be null");
            Objects.requireNonNull(testCommand, "testCommand must not be null");
            if (testCommand.isEmpty()) {
                throw new IllegalArgumentException("testCommand must not be empty");
            }
            return new CodingSession(this);
        }
    }
}
