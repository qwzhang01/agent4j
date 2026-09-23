package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.run.FailureKind;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.contract.ContractAwareToolExecutor;
import io.github.qwzhang01.agent.core.tool.contract.SideEffectLevel;
import io.github.qwzhang01.agent.core.tool.contract.ToolDefinition;
import io.github.qwzhang01.agent.core.tool.contract.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * / 2.5 acceptance (harness roadmap).
 * <p>
 * Roadmap acceptance lines covered:
 * <ul>
 *   <li>secure assembly needs no hand-stacked eight-layer decorators</li>
 *   <li>side-effect tools demand approval in a secure assembly; read-shaped
 *       ones run automatic</li>
 *   <li>unauthorized (denied) tools never execute and never count as success</li>
 *   <li>the unsafe path exists, is named Unsafe, and keeps legacy behavior</li>
 *   <li>governance sits OUTSIDE the observing executor — the order contract</li>
 * </ul>
 */
class SecureAssemblyTest {

    /** Read-shaped tool: secure assembly lets it run without approval. */
    static final class ReadTool implements Tool {
        final AtomicInteger executions = new AtomicInteger();
        @Override public String getName() { return "read_config"; }
        @Override public String getDescription() { return "reads config"; }
        @Override public String getParametersSchema() { return null; }
        @Override public ToolDefinition definition() {
            return ToolDefinition.builder("read_config")
                    .sideEffectLevel(SideEffectLevel.READ_ONLY).build();
        }
        @Override public String execute(com.fasterxml.jackson.databind.JsonNode a) {
            executions.incrementAndGet();
            return "config-value";
        }
    }

    /** Destructive tool: secure assembly must ask approval first. */
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

    /** Model client that calls one tool then answers. */
    static final class ToolCallingClient implements io.github.qwzhang01.agent.core.client.ModelClient {
        final String toolName;
        int turn = 0;
        ToolCallingClient(String toolName) { this.toolName = toolName; }

        @Override
        public ModelResponse chat(ModelRequest request) {
            if (turn++ == 0) {
                return ModelResponse.toolCalls(List.of(ToolCall.of(
                        "call-1", toolName, (com.fasterxml.jackson.databind.JsonNode) null)));
            }
            return ModelResponse.text("done");
        }

        @Override
        public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
            return java.util.stream.Stream.of(new StreamEvent.Done(chat(request)));
        }
    }

    @Test
    void secureAssemblyRunsReadToolsWithoutApproval() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        ReadTool read = new ReadTool();
        registry.register(read);

        Agent agent = SecureAgentBuilder.secure("secure-agent",
                        new ToolCallingClient("read_config"), registry)
                .approvalService(ConsoleApprovalService.autoReject()) // even with a rejecting approver
                .build();

        String out = agent.run("read the config", new io.github.qwzhang01.agent.core.agent.AgentState());
        assertEquals("done", out);
        assertEquals(1, read.executions.get(), "read-shaped tool must not need approval");
    }

    @Test
    void secureAssemblyBlocksDestructiveToolWithoutApproval() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        DestructiveTool delete = new DestructiveTool();
        registry.register(delete);

        Agent agent = SecureAgentBuilder.secure("secure-agent",
                        new ToolCallingClient("delete_file"), registry)
                .approvalService(ConsoleApprovalService.autoReject())
                .build();

        agent.run("delete it", new io.github.qwzhang01.agent.core.agent.AgentState());
        assertEquals(0, delete.executions.get(),
                "destructive tool must never execute without approval");
    }

    @Test
    void secureAssemblyRunsDestructiveToolWhenApproved() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        DestructiveTool delete = new DestructiveTool();
        registry.register(delete);

        Agent agent = SecureAgentBuilder.secure("secure-agent",
                        new ToolCallingClient("delete_file"), registry)
                .approvalService(ConsoleApprovalService.autoApprove())
                .build();

        agent.run("delete it", new io.github.qwzhang01.agent.core.agent.AgentState());
        assertEquals(1, delete.executions.get(), "approved destructive tool executes");
    }

    @Test
    void secureAssemblyNeedsNoHandStackedDecorators() {
        // The whole point of 2.4: one builder call, full stack wired.
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new ReadTool());

        AgentConfig config = SecureAgentBuilder.secure("secure-agent",
                        new ToolCallingClient("read_config"), registry)
                .buildConfig();

        assertNotNull(config.getToolExecutor(), "governed executor wired by default");
        assertInstanceOf(io.github.qwzhang01.agent.core.tool.contract.ContractAwareToolExecutor.class,
                config.getToolExecutor(),
                "validation sits outside governance so malformed calls never consume approval");
    }

    @Test
    void zeroConfigDestructiveToolDoesNotExecute() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        DestructiveTool delete = new DestructiveTool();
        registry.register(delete);

        Agent agent = SecureAgentBuilder.secure("secure-agent",
                        new ToolCallingClient("delete_file"), registry)
                .build();

        agent.run("delete it", new io.github.qwzhang01.agent.core.agent.AgentState());
        assertEquals(0, delete.executions.get(),
                "deny-on-absence: zero-config destructive tools must not run");
    }

    @Test
    void governedDenialIsNotRecordedAsSuccess() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        DestructiveTool delete = new DestructiveTool();
        registry.register(delete);
        ContractAwareToolExecutor stack =
                (ContractAwareToolExecutor) SecureAgentBuilder.secure("secure-agent",
                                new ToolCallingClient("delete_file"), registry)
                        .buildConfig()
                        .getToolExecutor();
        RunContext ctx = RunContext.builder().runId("run-deny").build();
        String out = stack.execute(new ToolCall("c1", "delete_file", null), ctx);
        assertTrue(out.startsWith("[DENIED]"), out);
        assertEquals(0, delete.executions.get());
        ToolResult envelope = stack.lastResult("run-deny");
        assertEquals(ToolResult.Outcome.BUSINESS_REJECTED, envelope.outcome());
        assertEquals(FailureKind.PERMISSION_DENIED, envelope.failureKind());
    }

    @Test
    void invalidArgumentsNeverReachApproval() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        java.util.concurrent.atomic.AtomicInteger approvals = new java.util.concurrent.atomic.AtomicInteger();
        Tool schemaTool = new Tool() {
            @Override public String getName() { return "delete_file"; }
            @Override public String getDescription() { return "deletes"; }
            @Override public String getParametersSchema() {
                return """
                        {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""";
            }
            @Override public ToolDefinition definition() {
                return ToolDefinition.builder("delete_file")
                        .sideEffectLevel(SideEffectLevel.DESTRUCTIVE)
                        .inputSchema(getParametersSchema())
                        .build();
            }
            @Override public String execute(com.fasterxml.jackson.databind.JsonNode a) {
                return "deleted";
            }
        };
        registry.register(schemaTool);

        io.github.qwzhang01.agent.core.tool.ToolExecutor stack =
                SecureAgentBuilder.secure("secure-agent",
                                new ToolCallingClient("delete_file"), registry)
                        .approvalService((call, runId) -> {
                            approvals.incrementAndGet();
                            return true;
                        })
                        .buildConfig()
                        .getToolExecutor();

        String out = stack.execute(new ToolCall("c1", "delete_file",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{}")),
                RunContext.create());
        assertTrue(out.startsWith("[INVALID_TOOL_ARGUMENTS]"), out);
        assertEquals(0, approvals.get(), "validation must sit outside approval");
    }

    @Test
    void unsafePathKeepsLegacyBehaviorAndIsNamedUnsafe() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        DestructiveTool delete = new DestructiveTool();
        registry.register(delete);

        // Unsafe assembly: destructive tool runs with zero ceremony.
        Agent agent = UnsafeAgentBuilder.unsafe("demo-agent",
                new ToolCallingClient("delete_file"), registry).build();

        agent.run("delete it", new io.github.qwzhang01.agent.core.agent.AgentState());
        assertEquals(1, delete.executions.get(), "unsafe path = frozen 0.1.3 behavior");
        // And the builder class name itself carries the warning.
        assertTrue(UnsafeAgentBuilder.class.getSimpleName().contains("Unsafe"));
    }

    /**
     * Order contract : the observing executor must sit OUTSIDE
     * the governed executor — otherwise it cannot see denials (a denied call
     * never reaches the inner executor, so an inner observer would record
     * silence instead of a rejection).
     */
    @Test
    void observingExecutorMustSitOutsideGovernance() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        DestructiveTool delete = new DestructiveTool();
        registry.register(delete);

        // Correct order: observer wraps governed (governance inside).
        io.github.qwzhang01.agent.core.tool.ToolExecutor inner =
                GovernedToolExecutor.builder(
                                new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry))
                        .permissionChecker(new PermissionChecker(new ToolPolicy(ToolPermission.DENY)))
                        .build();
        CountingExecutor observing = new CountingExecutor(inner);

        ToolCall call = new ToolCall("c1", "delete_file", null);
        String out = observing.execute(call);
        assertTrue(out.startsWith("[DENIED]"), "got: " + out);
        assertEquals(1, observing.calls, "outer observer sees the denial");
        assertEquals(0, delete.executions.get());

        // Wrong order: governed wraps observer (governance outside = observer
        // inside) — the observer misses denials. The test asserts the
        // correct-order property and documents the wrong-order failure mode.
        CountingExecutor innerObserver = new CountingExecutor(
                new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));
        io.github.qwzhang01.agent.core.tool.ToolExecutor governed =
                GovernedToolExecutor.builder(innerObserver)
                        .permissionChecker(new PermissionChecker(new ToolPolicy(ToolPermission.DENY)))
                        .build();
        String out2 = governed.execute(call);
        assertTrue(out2.startsWith("[DENIED]"));
        assertEquals(0, innerObserver.calls,
                "wrong order: inner observer is blind to the denial — exactly why the contract fixes the order");
    }

    /** Outer observing executor that counts every call it forwards. */
    static final class CountingExecutor implements io.github.qwzhang01.agent.core.tool.ToolExecutor {
        final io.github.qwzhang01.agent.core.tool.ToolExecutor delegate;
        int calls;
        CountingExecutor(io.github.qwzhang01.agent.core.tool.ToolExecutor delegate) {
            this.delegate = delegate;
        }
        @Override public String execute(ToolCall toolCall) {
            calls++;
            return delegate.execute(toolCall);
        }
        @Override public String execute(ToolCall toolCall, RunContext ctx) {
            calls++;
            return delegate.execute(toolCall, ctx);
        }
    }
}
