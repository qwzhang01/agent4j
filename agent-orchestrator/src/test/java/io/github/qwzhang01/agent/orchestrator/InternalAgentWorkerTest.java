package io.github.qwzhang01.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11 M11.1 tests: the unified worker abstraction + internal worker.
 */
class InternalAgentWorkerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ============ Test agents ============

    /** A fake agent that returns a fixed answer. */
    private static Agent fixedAgent(String output) {
        return new Agent() {
            @Override public String run(String userInput) { return output; }
            @Override public String run(String userInput, AgentState state) { return output; }
            @Override public AgentConfig getConfig() { return null; }
        };
    }

    /** A fake agent that always throws. */
    private static Agent throwingAgent(RuntimeException e) {
        return new Agent() {
            @Override public String run(String userInput) { throw e; }
            @Override public String run(String userInput, AgentState state) { throw e; }
            @Override public AgentConfig getConfig() { return null; }
        };
    }

    /** A fake agent that records every input it receives. */
    private static Agent recordingAgent(List<String> inputs, String output) {
        return new Agent() {
            @Override public String run(String userInput) {
                inputs.add(userInput);
                return output;
            }
            @Override public String run(String userInput, AgentState state) {
                inputs.add(userInput);
                return output;
            }
            @Override public AgentConfig getConfig() { return null; }
        };
    }

    // ============ execute contract ============

    @Test
    void execute_success_returnsResultWithOutputAndMetrics() {
        InternalAgentWorker worker = InternalAgentWorker.of("researcher", fixedAgent("research done"));

        WorkerResult result = worker.execute(WorkerTask.of("researcher", "research", "do the research"));

        assertTrue(result.success());
        assertEquals("research done", result.output());
        assertEquals(1, result.attempts());
        assertTrue(result.durationMs() >= 0);
        assertNull(result.error());
        assertEquals(0, result.totalTokens());  // v1: not wired yet, honest zero
    }

    @Test
    void execute_agentThrows_returnsFailureNotException() {
        InternalAgentWorker worker =
                InternalAgentWorker.of("broken", throwingAgent(new RuntimeException("boom")));

        // The D4 contract: never throws -- failure is data
        WorkerResult result = assertDoesNotThrow(
                () -> worker.execute(WorkerTask.of("broken", "anything", "x")));

        assertFalse(result.success());
        assertNull(result.output());
        assertNotNull(result.error());
        assertTrue(result.error().contains("boom"));
        assertTrue(result.error().contains("RuntimeException"));
    }

    @Test
    void execute_resultCarriesTaskIdAndWorkerName() {
        InternalAgentWorker worker = InternalAgentWorker.of("w1", fixedAgent("ok"));
        WorkerTask task = WorkerTask.of("w1", "t", "hello");

        WorkerResult result = worker.execute(task);

        assertEquals(task.taskId(), result.taskId());
        assertEquals("w1", result.workerName());
    }

    // ============ Prompt extraction ============

    @Test
    void execute_promptFieldBecomesAgentInput() {
        List<String> inputs = new ArrayList<>();
        InternalAgentWorker worker = InternalAgentWorker.of("w", recordingAgent(inputs, "ok"));

        worker.execute(WorkerTask.of("w", "t", "hello world"));

        assertEquals(1, inputs.size());
        assertEquals("hello world", inputs.get(0));
    }

    @Test
    void execute_payloadWithoutPromptField_fallsBackToJsonString() {
        List<String> inputs = new ArrayList<>();
        InternalAgentWorker worker = InternalAgentWorker.of("w", recordingAgent(inputs, "ok"));

        worker.execute(WorkerTask.of("w", "t", MAPPER.createObjectNode().put("x", 1)));

        assertEquals(1, inputs.size());
        assertTrue(inputs.get(0).contains("\"x\""));
        assertTrue(inputs.get(0).contains("1"));
    }

    @Test
    void execute_customPromptExtractor_overridesDefault() {
        List<String> inputs = new ArrayList<>();
        InternalAgentWorker worker = new InternalAgentWorker(
                "w", recordingAgent(inputs, "ok"),
                new io.github.qwzhang01.agent.mcp.a2a.AgentCard("w", "", List.of(), "internal:w", "1.0"),
                task -> "prefix:" + task.taskType());

        worker.execute(WorkerTask.of("w", "research", "ignored payload"));

        assertEquals("prefix:research", inputs.get(0));
    }

    // ============ AgentCard ============

    @Test
    void card_declaresNameAndSkills() {
        InternalAgentWorker worker =
                InternalAgentWorker.of("researcher", fixedAgent("ok"), "research", "summarize");

        var card = worker.card();

        assertEquals("researcher", card.name());
        assertEquals(List.of("research", "summarize"), card.skills());
        assertEquals("internal:researcher", card.endpoint());
        assertEquals("researcher", worker.name());
    }

    // ============ WorkerTask factory ============

    @Test
    void workerTask_ofFactory_generatesIdAndPromptPayload() {
        WorkerTask task = WorkerTask.of("w", "research", "do it");

        assertNotNull(task.taskId());
        assertEquals("w", task.workerName());
        assertEquals("research", task.taskType());
        assertEquals("do it", task.prompt());
        assertEquals(0, task.timeoutMs());
        assertEquals(0, task.maxRetries());
    }

    // ============ Integration: real SimpleAgent via MockModelClient ============

    @Test
    void execute_realSimpleAgent_fullLoop() {
        MockModelClient model = MockModelClient.scripted().respondText("mock answer");
        SimpleAgent agent = new SimpleAgent(new AgentConfig(
                "test-agent", "You are a test agent.", model, null));

        InternalAgentWorker worker = InternalAgentWorker.of("real", agent, "answer");

        WorkerResult result = worker.execute(WorkerTask.of("real", "answer", "what is the answer?"));

        assertTrue(result.success());
        assertEquals("mock answer", result.output());
    }

    @Test
    void execute_realSimpleAgent_modelExhausted_reportsFailure() {
        // scripted client with no responses -> ModelException inside the loop
        MockModelClient model = MockModelClient.scripted();
        SimpleAgent agent = new SimpleAgent(new AgentConfig("err-agent", null, model, null));

        InternalAgentWorker worker = InternalAgentWorker.of("err", agent);

        WorkerResult result = worker.execute(WorkerTask.of("err", "t", "anything"));

        // loop errors surface as failure data, not exceptions
        assertFalse(result.success());
        assertNotNull(result.error());
    }
}
