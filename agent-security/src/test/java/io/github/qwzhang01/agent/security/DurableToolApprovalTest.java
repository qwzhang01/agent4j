package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.approval.InMemoryApprovalStore;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.contract.SideEffectLevel;
import io.github.qwzhang01.agent.core.tool.contract.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable tool approval: PENDING pauses the loop; a later resume after
 * an operator decision executes the tool exactly once.
 */
class DurableToolApprovalTest {

    static final class DestructiveTool implements Tool {
        final AtomicInteger executions = new AtomicInteger();
        @Override public String getName() { return "delete_file"; }
        @Override public String getDescription() { return "deletes a file"; }
        @Override public String getParametersSchema() { return null; }
        @Override public ToolDefinition definition() {
            return ToolDefinition.builder("delete_file")
                    .sideEffectLevel(SideEffectLevel.DESTRUCTIVE).build();
        }
        @Override public String execute(com.fasterxml.jackson.databind.JsonNode a) {
            executions.incrementAndGet();
            return "deleted";
        }
    }

    static final class ToolCallingClient implements io.github.qwzhang01.agent.core.client.ModelClient {
        int turn;
        @Override
        public ModelResponse chat(ModelRequest request) {
            if (turn++ == 0) {
                return ModelResponse.toolCalls(List.of(ToolCall.of(
                        "call-1", "delete_file", (com.fasterxml.jackson.databind.JsonNode) null)));
            }
            return ModelResponse.text("done");
        }
        @Override
        public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
            return java.util.stream.Stream.of(new StreamEvent.Done(chat(request)));
        }
    }

    @Test
    void pendingApprovalPausesThenResumeExecutesOnce() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        DestructiveTool delete = new DestructiveTool();
        registry.register(delete);
        DurableToolApprovalService approval =
                new DurableToolApprovalService(new InMemoryApprovalStore());
        ToolCallingClient client = new ToolCallingClient();
        Agent agent = SecureAgentBuilder.secure("secure-agent", client, registry)
                .approvalService(approval)
                .build();

        RunContext ctx = RunContext.builder().runId("run-appr").build();
        AgentState state = new AgentState();
        ToolCall call = ToolCall.of("call-1", "delete_file",
                (com.fasterxml.jackson.databind.JsonNode) null);

        String first = agent.run("delete it", state, ctx);
        assertEquals(AgentState.Status.WAITING_APPROVAL, state.getStatus());
        assertEquals(0, delete.executions.get());
        assertTrue(first.contains("waiting for approval"));

        approval.approve("run-appr", call, "ops", "ok");
        String second = agent.resume(state, ctx);
        assertEquals(1, delete.executions.get(), "tool runs once after approval");
        assertEquals("done", second);
        assertEquals(AgentState.Status.DONE, state.getStatus());
    }
}
