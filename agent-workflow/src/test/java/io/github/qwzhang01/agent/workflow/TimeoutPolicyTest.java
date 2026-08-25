package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.runtime.TimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6.3: run-level and node-level timeout. Timeout fails the run
 * (FAILED + message contains "timed out") and does not take onError edges.
 */
class TimeoutPolicyTest {

    @Test
    void noneRejectsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> TimeoutPolicy.of(Duration.ofMillis(-1), Duration.ZERO));
    }

    @Test
    void runTimeoutFailsAfterSlowNodeAndSkipsTheRest() {
        AtomicBoolean secondRan = new AtomicBoolean(false);
        Workflow wf = Workflow.builder("run-timeout")
                .node(ActionNode.of("slow", ctx -> {
                    Thread.sleep(80);
                    return "slow-done";
                }))
                .node(ActionNode.of("after", ctx -> {
                    secondRan.set(true);
                    return "after-done";
                }))
                .edge(Workflow.START, "slow")
                .edge("slow", "after")
                .edge("after", Workflow.END)
                .build();

        ExecutionResult result = new RunManager().start(
                wf, "in", TimeoutPolicy.runOnly(Duration.ofMillis(40)));

        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().toLowerCase().contains("timed out"),
                result.errorMessage());
        assertFalse(secondRan.get(), "node after the run-timeout boundary must not run");
        assertTrue(result.state().get("slow") != null, "the node that already finished stays on the board");
        assertTrue(result.state().get("after") == null);
    }

    @Test
    void nodeTimeoutFailsHangingNodeWithoutOnErrorRoute() {
        AtomicBoolean fallbackRan = new AtomicBoolean(false);
        Workflow wf = Workflow.builder("node-timeout")
                .node(ActionNode.of("hang", ctx -> {
                    Thread.sleep(400);
                    return "should-not-return";
                }))
                .node(ActionNode.of("fallback", ctx -> {
                    fallbackRan.set(true);
                    return "fallback";
                }))
                .edge(Workflow.START, "hang")
                .edge("hang", Workflow.END)
                .onError("hang", "fallback")
                .edge("fallback", Workflow.END)
                .build();

        ExecutionResult result = new RunManager().start(
                wf, "in", TimeoutPolicy.of(Duration.ofMillis(50), Duration.ZERO));

        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("timed out"), result.errorMessage());
        assertFalse(fallbackRan.get(), "node timeout must not take the onError edge");
    }

    @Test
    void unlimitedPolicyLetsASlowNodeFinish() {
        Workflow wf = Workflow.builder("no-timeout")
                .node(ActionNode.of("slow", ctx -> {
                    Thread.sleep(40);
                    return "ok";
                }))
                .edge(Workflow.START, "slow")
                .edge("slow", Workflow.END)
                .build();

        ExecutionResult result = new RunManager().start(wf, "in", TimeoutPolicy.none());
        assertTrue(result.isSucceeded());
        assertEqualsSafe("ok", result.output());
    }

    private static void assertEqualsSafe(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
