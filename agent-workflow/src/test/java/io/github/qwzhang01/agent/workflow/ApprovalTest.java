package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5.5 - human approval node semantics.
 */
class ApprovalTest {

    private Workflow approvalFlow(ApprovalService service) {
        return Workflow.builder("approval-flow")
                .node(HumanApprovalNode.of("approval", "refund of $99", service))
                .node(ActionNode.of("execute", ctx -> "refund executed for: " + ctx.input()))
                .edge(Workflow.START, "approval")
                .edge("approval", "execute")
                .edge("execute", Workflow.END)
                .build();
    }

    @Test
    void approvalAllowsWorkflowToContinue() {
        MockApprovalService service = MockApprovalService.autoApprove();

        ExecutionResult result = new GraphRuntime().run(approvalFlow(service), "order#1001");

        assertTrue(result.isSucceeded());
        // Approved node passes the payload through to downstream nodes
        assertEquals("refund executed for: order#1001", result.output());
        assertEquals(1, service.callCount());
    }

    @Test
    void rejectionFailsTheWorkflow() {
        Workflow wf = approvalFlow(MockApprovalService.autoReject());

        ExecutionResult result = new GraphRuntime().run(wf, "order#1002");

        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("rejected"));
        // Downstream node never executed
        assertNull(result.state().get("execute"));
    }

    @Test
    void rejectionCanBeRoutedViaOnErrorEdge() {
        Workflow wf = Workflow.builder("rejection-handler")
                .node(HumanApprovalNode.of("approval", "payout", MockApprovalService.autoReject()))
                .node(ActionNode.of("rejected", ctx -> "notified requester: " + ctx.input()))
                .edge(Workflow.START, "approval")
                .edge("approval", Workflow.END)
                .onError("approval", "rejected")
                .edge("rejected", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "req#7");

        assertTrue(result.isSucceeded());
        assertEquals("notified requester: Approval rejected at node 'approval'", result.output());
    }
}
