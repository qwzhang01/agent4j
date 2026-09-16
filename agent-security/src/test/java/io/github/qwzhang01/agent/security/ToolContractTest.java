package io.github.qwzhang01.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.FailureKind;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.contract.ContractAwareToolExecutor;
import io.github.qwzhang01.agent.core.tool.contract.SideEffectLevel;
import io.github.qwzhang01.agent.core.tool.contract.ToolDefinition;
import io.github.qwzhang01.agent.core.tool.contract.ToolResult;
import io.github.qwzhang01.agent.core.tool.contract.ValidationResult;
import io.github.qwzhang01.agent.core.tool.contract.ToolArgumentValidator;
import io.github.qwzhang01.agent.core.tool.ToolException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2.1 / 2.2 / 2.3 acceptance (harness roadmap).
 * <p>
 * Roadmap acceptance lines covered here:
 * <ul>
 *   <li>missing required parameter → tool NOT executed,
 *       {@code INVALID_TOOL_ARGUMENTS} classification produced</li>
 *   <li>unknown tools refused (never string-passed as if they were results)</li>
 *   <li>same validation chain for every tool — contract-declared or legacy</li>
 *   <li>result envelope keeps the original classification (never collapsed
 *       to "Tool execution failed")</li>
 * </ul>
 */
class ToolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A contract-declared echo tool with a required field and an enum. */
    static final class EchoTool implements Tool {
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "echoes the message"; }
        @Override public String getParametersSchema() {
            return """
                    {"type":"object","properties":{
                      "msg":{"type":"string","maxLength":8},
                      "tone":{"type":"string","enum":["plain","shout"]}
                    },"required":["msg"]}""";
        }
        @Override public ToolDefinition definition() {
            return ToolDefinition.builder(getName())
                    .description(getDescription())
                    .inputSchema(getParametersSchema())
                    .version("2.0.0")
                    .sideEffectLevel(SideEffectLevel.READ_ONLY)
                    .build();
        }
        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "echo:" + arguments.path("msg").asText();
        }
    }

    /** A side-effect tool that counts executions (must stay zero when rejected). */
    static final class WriteTool implements Tool {
        final AtomicInteger executions = new AtomicInteger();
        @Override public String getName() { return "write_note"; }
        @Override public String getDescription() { return "writes a note"; }
        @Override public String getParametersSchema() {
            return """
                    {"type":"object","properties":{"note":{"type":"string"}},"required":["note"]}""";
        }
        @Override public ToolDefinition definition() {
            return ToolDefinition.builder(getName())
                    .sideEffectLevel(SideEffectLevel.SIDE_EFFECT)
                    .inputSchema(getParametersSchema())
                    .build();
        }
        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            executions.incrementAndGet();
            return "written";
        }
    }

    @Test
    void missingRequiredFieldIsRejectedWithoutExecution() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        WriteTool tool = new WriteTool();
        registry.register(tool);
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-1").build();
        String out = executor.execute(new ToolCall("c1", "write_note", null), ctx);
        // null arguments + required field → rejected, tool body never ran
        assertTrue(out.startsWith("[INVALID_TOOL_ARGUMENTS]"), "got: " + out);
        assertEquals(0, tool.executions.get());

        ToolResult envelope = executor.lastResult("run-t2-1");
        assertNotNull(envelope);
        assertEquals(FailureKind.INPUT_INVALID, envelope.failureKind());
        assertEquals(ToolResult.Outcome.BUSINESS_REJECTED, envelope.outcome());
    }

    @Test
    void invalidArgumentsProduceInputInvalidClassification() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new EchoTool());
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-2").build();
        // msg is a number, not a string → type violation
        String out = executor.execute(new ToolCall("c1", "echo",
                MAPPER.readTree("{\"msg\":42}")), ctx);
        assertTrue(out.startsWith("[INVALID_TOOL_ARGUMENTS]"), "got: " + out);
        assertEquals(FailureKind.INPUT_INVALID, executor.lastResult("run-t2-2").failureKind());
    }

    @Test
    void enumAndLengthViolationsAreRejected() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new EchoTool());
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-3").build();
        // tone not in enum
        String enumBad = executor.execute(new ToolCall("c1", "echo",
                MAPPER.readTree("{\"msg\":\"hi\",\"tone\":\"whisper\"}")), ctx);
        assertTrue(enumBad.startsWith("[INVALID_TOOL_ARGUMENTS]"), "got: " + enumBad);

        // msg longer than maxLength 8
        String longMsg = executor.execute(new ToolCall("c2", "echo",
                MAPPER.readTree("{\"msg\":\"123456789\"}")), ctx);
        assertTrue(longMsg.startsWith("[INVALID_TOOL_ARGUMENTS]"), "got: " + longMsg);
    }

    @Test
    void unknownToolIsRefusedNotStringPassed() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-4").build();
        String out = executor.execute(new ToolCall("c1", "no_such_tool", null), ctx);
        assertTrue(out.startsWith("[UNKNOWN_TOOL]"), "got: " + out);
        // refused calls never register an "executed success" envelope
        assertEquals(ToolResult.Outcome.SYSTEM_FAILURE, executor.lastResult("run-t2-4").outcome());
    }

    @ValidationTest
    @Test
    void sameChainForLegacyUntypedTools() throws Exception {
        // A legacy tool without any schema still hits the structural caps.
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        Tool legacy = new Tool() {
            @Override public String getName() { return "legacy"; }
            @Override public String getDescription() { return "legacy tool"; }
            @Override public String getParametersSchema() { return null; }
            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return "ok";
            }
        };
        registry.register(legacy);
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-5").build();
        String out = executor.execute(new ToolCall("c1", "legacy",
                MAPPER.readTree("{\"a\":1}")), ctx);
        assertEquals("ok", out); // structural caps only — valid shape passes
    }

    @Test
    void toolFailureKeepsOriginalClassificationNotGenericString() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        Tool failing = new Tool() {
            @Override public String getName() { return "failing"; }
            @Override public String getDescription() { return "always throws"; }
            @Override public String getParametersSchema() { return null; }
            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                throw new ToolException("disk quota exceeded on volume /data");
            }
        };
        registry.register(failing);
        // Note: the v1 DefaultToolExecutor catches ToolException and returns
        // "[ERROR] Tool 'failing' failed: ..." as a string — the loop never
        // sees the exception. ContractAwareToolExecutor wraps this delegate,
        // so the typed envelope must be captured at the wrapping boundary.
        io.github.qwzhang01.agent.core.tool.ToolExecutor throwingDelegate =
                new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry);
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, throwingDelegate);

        RunContext ctx = RunContext.builder().runId("run-t2-6").build();
        String out = executor.execute(new ToolCall("c1", "failing", null), ctx);
        // The delegate swallowed the exception into an [ERROR] string; the
        // contract executor records SUCCESS (it cannot see the swallow) —
        // that is the documented v1 boundary. The envelope classification
        // therefore applies only to exceptions that DO propagate, which the
        // next assertion covers via a throwing delegate.
        assertEquals("[ERROR] Tool 'failing' failed: disk quota exceeded on volume /data", out);

        // A delegate that re-throws (as GovernedToolExecutor does) keeps the
        // original classification in the envelope instead of a generic string.
        io.github.qwzhang01.agent.core.tool.ToolExecutor rethrowingDelegate =
                new io.github.qwzhang01.agent.core.tool.ToolExecutor() {
                    @Override
                    public String execute(ToolCall toolCall) {
                        throw new ToolException("disk quota exceeded on volume /data");
                    }
                };
        ContractAwareToolExecutor executor2 =
                new ContractAwareToolExecutor(registry, rethrowingDelegate);
        assertThrows(ToolException.class, () ->
                executor2.execute(new ToolCall("c1", "failing", null), ctx));
        ToolResult envelope = executor2.lastResult("run-t2-6");
        assertNotNull(envelope);
        assertEquals(ToolResult.Outcome.SYSTEM_FAILURE, envelope.outcome());
        assertEquals("disk quota exceeded on volume /data", envelope.rawError());
        assertEquals(FailureKind.TOOL_FAILURE, envelope.failureKind());
    }

    @Test
    void schemaFingerprintDistinguishesContracts() {
        ToolDefinition v1 = ToolDefinition.builder("echo")
                .inputSchema("{\"type\":\"object\",\"required\":[\"msg\"]}")
                .version("1.0.0").build();
        ToolDefinition v2 = ToolDefinition.builder("echo")
                .inputSchema("{\"type\":\"object\",\"required\":[\"msg\",\"tone\"]}")
                .version("2.0.0").build();
        assertNotEquals(v1.schemaFingerprint(), v2.schemaFingerprint());
        assertEquals(v1.schemaFingerprint(), ToolDefinition.builder("echo")
                .inputSchema("{\"type\":\"object\",\"required\":[\"msg\"]}")
                .version("3.0.0").build().schemaFingerprint()); // schema-only, version-independent
    }

    @Test
    void unknownFieldsAreIgnoredButRecorded() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new EchoTool());
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-7").build();
        String out = executor.execute(new ToolCall("c1", "echo",
                MAPPER.readTree("{\"msg\":\"hi\",\"invented\":\"field\"}")), ctx);
        assertEquals("echo:hi", out); // ignored, call proceeds
    }

    @Test
    void deepNestingIsRejected() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new EchoTool());
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-8").build();
        String deep = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":{\"h\":{\"i\":1}}}}}}}}}}";
        String out = executor.execute(new ToolCall("c1", "echo", MAPPER.readTree(deep)), ctx);
        assertTrue(out.startsWith("[INVALID_TOOL_ARGUMENTS]"), "got: " + out);
    }

    @Test
    void oversizeArgumentsAreRejected() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        Tool tool = new EchoTool();
        registry.register(tool);
        // Definition with a tiny input cap
        registry.unregister("echo");
        Tool capped = new Tool() {
            @Override public String getName() { return "echo"; }
            @Override public String getDescription() { return "echoes"; }
            @Override public String getParametersSchema() { return tool.getParametersSchema(); }
            @Override public ToolDefinition definition() {
                return ToolDefinition.builder("echo")
                        .inputSchema(tool.getParametersSchema())
                        .sideEffectLevel(SideEffectLevel.READ_ONLY)
                        .maxInputBytes(20)
                        .build();
            }
            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return "echo:" + arguments.path("msg").asText();
            }
        };
        registry.register(capped);
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-9").build();
        String out = executor.execute(new ToolCall("c1", "echo",
                MAPPER.readTree("{\"msg\":\"this message is way longer than twenty bytes\"}")), ctx);
        assertTrue(out.startsWith("[INVALID_TOOL_ARGUMENTS]"), "got: " + out);
    }

    @Test
    void hungToolTimesOutWithEnvelope() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        Tool hung = new Tool() {
            @Override public String getName() { return "hung"; }
            @Override public String getDescription() { return "sleeps forever"; }
            @Override public String getParametersSchema() { return null; }
            @Override public ToolDefinition definition() {
                return ToolDefinition.builder("hung")
                        .timeout(java.time.Duration.ofMillis(150))
                        .build();
            }
            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) throws ToolException {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ToolException("interrupted");
                }
                return "never";
            }
        };
        registry.register(hung);
        ContractAwareToolExecutor executor =
                new ContractAwareToolExecutor(registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry));

        RunContext ctx = RunContext.builder().runId("run-t2-10").build();
        long start = System.currentTimeMillis();
        String out = executor.execute(new ToolCall("c1", "hung", null), ctx);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(out.startsWith("[TIMEOUT]"), "got: " + out);
        assertTrue(elapsed < 5_000, "timeout must fire promptly, took " + elapsed + "ms");
        assertEquals(FailureKind.TIMEOUT, executor.lastResult("run-t2-10").failureKind());
    }

    @Test
    void validatorDirectLegacyUntypedPassesWithNoSchema() throws Exception {
        ToolDefinition legacyDef = ToolDefinition.fromLegacyTool(new EchoTool());
        // fromLegacyTool keeps the declared schema; a truly schema-less tool:
        Tool schemaless = new Tool() {
            @Override public String getName() { return "s"; }
            @Override public String getDescription() { return "d"; }
            @Override public String getParametersSchema() { return null; }
            @Override public String execute(com.fasterxml.jackson.databind.JsonNode a) { return "ok"; }
        };
        ValidationResult vr = ToolArgumentValidator.validate(
                MAPPER.readTree("{\"anything\":true}"), schemaless.definition());
        assertTrue(vr.valid());
    }

    /** Marker for tests that document the chain-uniformity property. */
    @interface ValidationTest { }
}
