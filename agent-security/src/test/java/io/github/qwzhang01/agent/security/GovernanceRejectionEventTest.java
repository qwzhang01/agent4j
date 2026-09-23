package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * gap-closure: the governed tool boundary emits typed rejection
 * events through the loop. The {@code GovernedToolExecutor} has ALWAYS
 * returned {@code [DENIED]} / {@code [RATE_LIMITED]} strings (governance
 * stays a model-visible result the loop can self-correct on); what was
 * missing is the observability twin — {@code ToolValidationRejected} with
 * the governance stage. These tests pin the wiring that closes that gap:
 * permission DENY, approval rejection, missing-approval-service, and the
 * rate gate, each routed through a real {@code ReActAgentLoop}.
 */
class GovernanceRejectionEventTest {

    /** Scripted chat-only client: first response demands a tool, then text. */
    static final class ScriptedMock implements ModelClient {
        final Queue<ModelResponse> script = new LinkedBlockingQueue<>();

        ScriptedMock respond(ModelResponse... responses) {
            for (ModelResponse r : responses) {
                script.add(r);
            }
            return this;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            return script.poll();
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            ModelResponse r = script.poll();
            return Stream.of(new StreamEvent.Done(r));
        }
    }

    /** Minimal read-shaped tool stub (registers, executes trivially). */
    private Tool echoTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "echo stub"; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) { return "echo-ok"; }
        };
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolCall call(String id, String name) {
        return ToolCall.of(id, name, mapper.createObjectNode());
    }

    /** Assembly: GovernedToolExecutor(DefaultToolExecutor) through the loop. */
    private Agent governedAgent(InMemoryToolRegistry registry,
                                PermissionChecker checker,
                                ToolApprovalService approval) {
        GovernedToolExecutor governed = GovernedToolExecutor
                .builder(new DefaultToolExecutor(registry))
                .permissionChecker(checker)
                .approvalService(approval)
                .build();
        ReActAgentLoop loop = new ReActAgentLoop(governed);
        return new SimpleAgent(new AgentConfig("gov", "sys", new ScriptedMock(), registry, 5), loop);
    }

    /** Run one tool-call turn and collect the events. */
    private List<AgentEvent> runOneToolTurn(Agent agent, ToolCall toolCall) {
        ((ScriptedMock) agent.getConfig().getModelClient())
                .respond(ModelResponse.toolCalls(List.of(toolCall)))
                .respond(ModelResponse.text("recovered"));
        List<AgentEvent> events = new ArrayList<>();
        agent.stream("use the tool", new AgentState(), events::add);
        return events;
    }

    @Test
    @DisplayName("DENY policy emits ToolValidationRejected; [DENIED] maps to stage=approval")
    void denyPolicy_emitsTypedRejection() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echoTool("format_disk"));

        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("format_disk", ToolPermission.DENY);
        Agent agent = governedAgent(registry, new PermissionChecker(policy), null);

        List<AgentEvent> events = runOneToolTurn(agent, call("c1", "format_disk"));

        // Model-visible twin: the [DENIED] string pairs into history via
        // ToolFinished (checked through the event, below).
        AgentEvent.ToolValidationRejected rejected = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolValidationRejected)
                .map(e -> (AgentEvent.ToolValidationRejected) e)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "DENY must emit ToolValidationRejected, events: " + events));
        assertEquals("format_disk", rejected.toolName());
        assertEquals("approval", rejected.stage());
        assertTrue(rejected.reason().startsWith("[DENIED]"));
        // The run survives: the model sees the refusal string and recovers
        assertEquals(AgentState.Status.DONE,
                ((AgentEvent.Done) events.get(events.size() - 1)).state().getStatus());
    }

    @Test
    @DisplayName("REQUIRES_APPROVAL + rejection emits ToolValidationRejected stage=approval")
    void approvalRejection_emitsTypedRejection() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echoTool("delete_file"));

        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL);
        ToolApprovalService rejector = (toolCall, runId) -> false;
        Agent agent = governedAgent(registry, new PermissionChecker(policy), rejector);

        List<AgentEvent> events = runOneToolTurn(agent, call("c1", "delete_file"));

        AgentEvent.ToolValidationRejected rejected = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolValidationRejected)
                .map(e -> (AgentEvent.ToolValidationRejected) e)
                .findFirst().orElseThrow();
        assertEquals("delete_file", rejected.toolName());
        assertEquals("approval", rejected.stage());
        assertTrue(rejected.reason().startsWith("[DENIED]"),
                "reason: " + rejected.reason());
        assertTrue(rejected.reason().contains("Approval rejected"));
    }

    @Test
    @DisplayName("REQUIRES_APPROVAL without approval service: [DENIED] + typed event, run survives")
    void missingApprovalService_emitsTypedRejection() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echoTool("delete_file"));

        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL);
        Agent agent = governedAgent(registry, new PermissionChecker(policy), null);

        List<AgentEvent> events = runOneToolTurn(agent, call("c1", "delete_file"));

        AgentEvent.ToolValidationRejected rejected = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolValidationRejected)
                .map(e -> (AgentEvent.ToolValidationRejected) e)
                .findFirst().orElseThrow();
        assertEquals("approval", rejected.stage());
        assertTrue(rejected.reason().contains("no approval service"));
    }

    @Test
    @DisplayName("rate gate exceeded emits ToolValidationRejected stage=permission")
    void rateLimited_emitsTypedRejection() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echoTool("search"));

        ToolPolicy auto = new ToolPolicy(ToolPermission.AUTO);
        GovernedToolExecutor governed = GovernedToolExecutor
                .builder(new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(auto))
                .rateLimiter(new RateLimiter() {
                    @Override public boolean tryAcquire(String toolName) {
                        return false; // always throttled
                    }
                })
                .build();
        ReActAgentLoop loop = new ReActAgentLoop(governed);
        Agent agent = new SimpleAgent(
                new AgentConfig("gov", "sys", new ScriptedMock(), registry, 5), loop);

        List<AgentEvent> events = runOneToolTurn(agent, call("c1", "search"));

        AgentEvent.ToolValidationRejected rejected = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolValidationRejected)
                .map(e -> (AgentEvent.ToolValidationRejected) e)
                .findFirst().orElseThrow();
        assertEquals("search", rejected.toolName());
        assertEquals("permission", rejected.stage());
        assertTrue(rejected.reason().startsWith("[RATE_LIMITED]"));
    }

    @Test
    @DisplayName("AUTO tool through governed executor: no rejection events, plain execution")
    void autoTool_noRejectionEvents() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echoTool("get_time"));

        ToolPolicy auto = new ToolPolicy(ToolPermission.AUTO);
        Agent agent = governedAgent(registry, new PermissionChecker(auto), null);

        List<AgentEvent> events = runOneToolTurn(agent, call("c1", "get_time"));

        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ToolValidationRejected),
                "AUTO must not emit rejections, events: " + events);
        assertEquals(AgentState.Status.DONE,
                ((AgentEvent.Done) events.get(events.size() - 1)).state().getStatus());
    }
}
