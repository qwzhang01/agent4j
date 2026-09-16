package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.core.run.CancellationSource;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.JoinPolicy;
import io.github.qwzhang01.agent.workflow.nodes.ParallelNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 1.4 acceptance (harness roadmap): cancelling a run reaches every
 * parallel branch — each branch observes the shared cancellation token
 * through its own NodeContext and stops at its next boundary check.
 */
class ParallelCancelTest {

    /** Branch name -> whether its NodeContext carried the run's token. */
    static final Map<String, Boolean> sawToken = new ConcurrentHashMap<>();
    /** Branches that passed their check before the cancel went out. */
    static final List<String> passedBeforeCancel = new CopyOnWriteArrayList<>();

    @Test
    void cancelReachesEveryParallelBranchAndStopsThem() throws Exception {
        sawToken.clear();
        passedBeforeCancel.clear();

        CancellationSource source = new CancellationSource();
        RunContext ctx = RunContext.builder()
                .runId("run-cancel-1")
                .cancellationToken(source.token())
                .build();

        // Each branch: observe the token, wait cooperatively until cancelled,
        // then stop with a marker instead of returning normal output.
        ParallelNode fanout = ParallelNode.builder("fanout")
                .branch("left", ActionNode.of("leftStep", branchCtx -> {
                    sawToken.put("left",
                            branchCtx.runContext() != null
                                    && branchCtx.runContext().cancellationToken() != null);
                    awaitCancel(branchCtx, "left");
                    return "L";
                }))
                .branch("right", ActionNode.of("rightStep", branchCtx -> {
                    sawToken.put("right",
                            branchCtx.runContext() != null
                                    && branchCtx.runContext().cancellationToken() != null);
                    awaitCancel(branchCtx, "right");
                    return "R";
                }))
                .join(JoinPolicy.ALL_OF)
                .build();

        Workflow wf = Workflow.builder("parallel-cancel")
                .node(fanout)
                .edge(Workflow.START, "fanout")
                .edge("fanout", Workflow.END)
                .build();

        RunManager mgr = new RunManager();
        Thread runner = new Thread(() -> mgr.start(wf, "in", ctx));
        try {
            runner.start();
            Thread.sleep(200); // let both branches enter their wait loop
            source.cancel();
            runner.join(3000);
        } finally {
            if (runner.isAlive()) {
                runner.interrupt();
            }
        }

        assertTrue(sawToken.getOrDefault("left", false),
                "left branch must see the run's cancellation token");
        assertTrue(sawToken.getOrDefault("right", false),
                "right branch must see the run's cancellation token");
        assertFalse(runner.isAlive(),
                "both branches must stop after cancel (join within 3s)");
    }

    /**
     * Cooperative wait: poll the boundary check until the shared token is
     * cancelled. This mirrors what long-running nodes should do — check
     * {@code ctx.runContext().checkAlive()} at safe points instead of
     * blocking forever.
     */
    private static void awaitCancel(NodeContext ctx, String branch) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (ctx.runContext() != null && ctx.runContext().cancellationToken() != null
                    && ctx.runContext().cancellationToken().isCancelled()) {
                passedBeforeCancel.add(branch + ":stopped");
                throw new IllegalStateException(
                        branch + " branch stopped by cancellation");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        passedBeforeCancel.add(branch + ":timeout");
    }
}
