package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.MockApprovalService;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.runtime.ResumeToken;

/**
 * Stage 6 acceptance example: pause-resume via Checkpoint.
 * <pre>{@code
 * prepare -> approval (PAUSE) -> [human approves] -> resume -> execute_refund -> END
 * }</pre>
 * Demonstrates:
 * - HumanApprovalNode in async mode (pause-resume, not sync block)
 * - RunManager.start() -> PAUSED + ResumeToken
 * - Simulated human approval
 * - RunManager.resume() -> SUCCEEDED (from the paused node, not from scratch)
 * - StepRecord trace shows prepare was NOT re-executed (idempotent resume)
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.CheckpointExample
 */
public class CheckpointExample {

    public static void main(String[] args) {
        System.out.println("=== Stage 6: Checkpoint - Pause & Resume ===\n");

        MockApprovalService approval = MockApprovalService.autoApprove();

        Workflow wf = Workflow.builder("refund-flow")
                .node(ActionNode.of("prepare", ctx -> "prepared: " + ctx.input()))
                .node(HumanApprovalNode.of("approval", "refund request", approval))
                .node(ActionNode.of("execute_refund", ctx -> "refund executed for: " + ctx.input()))
                .edge(Workflow.START, "prepare")
                .edge("prepare", "approval")
                .edge("approval", "execute_refund")
                .edge("execute_refund", Workflow.END)
                .build();

        RunManager mgr = new RunManager();

        // ---- T1: Start -> runs until approval node pauses ----
        System.out.println("--- T1: Start workflow ---");
        System.out.println("User: I want a refund for order 1001\n");
        ExecutionResult r1 = mgr.start(wf, "order-1001");

        printTrace(r1);
        System.out.println("Status: " + r1.status());
        if (r1.isPaused()) {
            ResumeToken token = r1.resumeToken();
            System.out.println("Paused at: " + token.pausedAtNode());
            System.out.println("RunId: " + token.runId());
            System.out.println("\n⏳ Waiting for human approval...\n");

            // ---- Simulate human approval ----
            approval.setDecision(token.runId(), "approval", true);
            System.out.println("✅ Human approved the refund.\n");

            // ---- T2: Resume -> continues from approval node ----
            System.out.println("--- T2: Resume workflow ---");
            ExecutionResult r2 = mgr.resume(token.runId());
            printTrace(r2);
            System.out.println("Status: " + r2.status());
            System.out.println("Output: " + r2.output());

            // ---- Verify idempotent resume ----
            System.out.println("\n--- Verification ---");
            long prepareCount = r2.trace().stream()
                    .filter(r -> "prepare".equals(r.nodeId()))
                    .count();
            System.out.println("prepare node executions: " + prepareCount
                    + (prepareCount == 1 ? " ✅ (not re-executed on resume)" : " ❌ (should be 1)"));
        }

        System.out.println("\n=== Done ===");
    }

    private static void printTrace(ExecutionResult result) {
        System.out.println("Trace:");
        for (StepRecord step : result.trace()) {
            var icon = switch (step.status()) {
                case SUCCESS -> "✓";
                case FAILED -> "✗";
                case PAUSED -> "⏸";
                case CANCELLED -> "⊘";
            };
            System.out.printf("  %s %-16s %s%n", icon, step.nodeId(),
                    step.summary() != null ? step.summary() : "");
        }
        System.out.println();
    }
}
