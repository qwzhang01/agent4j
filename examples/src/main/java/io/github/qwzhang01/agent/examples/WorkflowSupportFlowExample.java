package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.MockApprovalService;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.AgentNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.nodes.ToolNode;

/**
 * Stage 5 acceptance example: the three-path support flow.
 * <pre>{@code
 * user request -> intent (AgentNode) -> conditional routing
 *   ├── QUERY  -> ticket lookup (ToolNode, deterministic)
 *   ├── REFUND -> human approval (HumanApprovalNode) -> execute
 *   └── other  -> human handoff (ActionNode)
 * }</pre>
 * Demonstrates: Agent as a graph node, deterministic tools, human-in-the-loop,
 * blackboard routing, and the step trace - all without a real LLM
 * (MockModelClient scripted mode).
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.WorkflowSupportFlowExample
 */
public class WorkflowSupportFlowExample {

    public static void main(String[] args) {
        System.out.println("=== Stage 5: Workflow Graph Runtime - Support Flow ===\n");

        runScenario("where is my order?", "QUERY");
        runScenario("I want a refund for order 1001", "REFUND");
        runScenario("your product is broken and I am angry", "COMPLAINT");

        System.out.println("=== Done ===");
    }

    private static void runScenario(String userInput, String mockedIntent) {
        System.out.println("─".repeat(60));
        System.out.println("User: " + userInput);
        System.out.println("  (mock model classifies as: " + mockedIntent + ")\n");

        ExecutionResult result = new GraphRuntime().run(buildFlow(mockedIntent), userInput);

        printTrace(result);
        if (result.isSucceeded()) {
            System.out.println("Workflow output: " + result.output() + "\n");
        } else {
            System.out.println("Workflow FAILED: " + result.errorMessage() + "\n");
        }
    }

    /** Build a fresh flow per scenario (scripted MockModelClient is consumed once). */
    private static Workflow buildFlow(String mockedIntent) {
        // The "intent agent": scripted mock returns a fixed classification
        MockModelClient model = MockModelClient.scripted().respondText(mockedIntent);
        Agent intentAgent = new SimpleAgent(new AgentConfig(
                "intent-agent",
                "Classify the request as QUERY, REFUND or COMPLAINT.",
                model, null, 5));

        return Workflow.builder("support-flow")
                // ---- Nodes ----
                .node(AgentNode.of("intent", intentAgent))
                .node(ToolNode.of("lookup", ticketLookupTool()))
                .node(HumanApprovalNode.of("approval", "refund request", MockApprovalService.autoApprove()))
                .node(ActionNode.of("execute_refund", ctx -> "refund executed for: " + ctx.input()))
                .node(ActionNode.of("handoff", ctx -> "transferred to human agent"))
                // ---- Edges: conditional routing on the blackboard ----
                .edge(Workflow.START, "intent")
                .edge("intent", "lookup").when(s -> "QUERY".equals(s.get("intent")))
                .edge("intent", "approval").when(s -> "REFUND".equals(s.get("intent")))
                .edge("intent", "handoff").otherwise()
                .edge("lookup", Workflow.END)
                .edge("approval", "execute_refund")
                .edge("execute_refund", Workflow.END)
                .edge("handoff", Workflow.END)
                .build();
    }

    private static Tool ticketLookupTool() {
        return new Tool() {
            @Override public String getName() { return "ticket_lookup"; }
            @Override public String getDescription() { return "look up a ticket by keyword"; }
            @Override public String getParametersSchema() { return null; }
            @Override public String execute(JsonNode arguments) {
                return "ticket#42: status=OPEN, will ship tomorrow";
            }
        };
    }

    private static void printTrace(ExecutionResult result) {
        System.out.println("Trace:");
        for (StepRecord step : result.trace()) {
            var icon = step.status() == StepRecord.Status.SUCCESS ? "✓" : "✗";
            System.out.printf("  %s %-16s %d attempt(s) %dms  %s%n",
                    icon, step.nodeId(), step.attempts(), step.durationMs(), step.summary());
        }
    }
}
