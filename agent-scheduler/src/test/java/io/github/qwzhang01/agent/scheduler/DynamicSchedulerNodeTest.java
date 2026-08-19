package io.github.qwzhang01.agent.scheduler;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.scheduler.nodes.DynamicSchedulerNode;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.AgentNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.runtime.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 7 completion: agent-driven scheduling.
 * The LLM (mocked) produces the scheduling intent at runtime; the
 * DynamicSchedulerNode reads it from the blackboard and registers the trigger.
 */
class DynamicSchedulerNodeTest {

    private ScheduledExecutorService executor;
    private RunManager runManager;
    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "test-dyn-scheduler");
            t.setDaemon(true);
            return t;
        });
        runManager = new RunManager();
        scheduler = new TaskScheduler(runManager, executor);
        runManager.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    // ============ LLM-driven event wait ============

    @Test
    void llmDecidedEventKeyDrivesRegistrationAndResume() throws Exception {
        // The "LLM" outputs a structured intent: wait for this CI event
        Agent decideAgent = new SimpleAgent(new AgentConfig(
                "decide-agent", "sys",
                MockModelClient.scripted().respondText(
                        "{\"action\":\"wait_event\",\"event_key\":\"ci-passed:pr-42\"}"),
                null, 5));

        Workflow wf = Workflow.builder("llm-event-flow")
                .node(AgentNode.of("decide", decideAgent))                 // LLM decides
                .node(DynamicSchedulerNode.of("wait", "decide"))           // registers from blackboard
                .node(ActionNode.of("after", ctx -> "got:" + ctx.input()))
                .edge(Workflow.START, "decide")
                .edge("decide", "wait")
                .edge("wait", "after")
                .edge("after", Workflow.END)
                .build();

        var r1 = runManager.start(wf, "watch PR #42 CI");
        assertTrue(r1.isPaused());
        assertEquals("wait", r1.resumeToken().pausedAtNode());

        // The LLM-chosen key (not a constructor constant) was registered
        assertTrue(scheduler.getEventBroker().getSubscriptions().containsKey("ci-passed:pr-42"));

        scheduler.fireEvent("ci-passed:pr-42", "green");
        Thread.sleep(300);

        var run = runManager.getRun(r1.resumeToken().runId());
        assertEquals(RunState.SUCCEEDED, run.getStatus());
    }

    // ============ LLM-driven schedule ============

    @Test
    void llmDecidedDelayDrivesScheduledResume() throws Exception {
        Agent decideAgent = new SimpleAgent(new AgentConfig(
                "decide-agent", "sys",
                MockModelClient.scripted().respondText(
                        "{\"action\":\"schedule\",\"delay_seconds\":1}"),
                null, 5));

        Workflow wf = Workflow.builder("llm-schedule-flow")
                .node(AgentNode.of("decide", decideAgent))
                .node(DynamicSchedulerNode.of("check-later", "decide"))
                .node(ActionNode.of("done", ctx -> "checked after resume"))
                .edge(Workflow.START, "decide")
                .edge("decide", "check-later")
                .edge("check-later", "done")
                .edge("done", Workflow.END)
                .build();

        var r1 = runManager.start(wf, "check later");
        assertTrue(r1.isPaused());

        // Auto-resume after the LLM-chosen delay (1s), no manual resume call
        Thread.sleep(1800);

        var run = runManager.getRun(r1.resumeToken().runId());
        assertEquals(RunState.SUCCEEDED, run.getStatus());
    }

    // ============ Governance gates ============

    @Test
    void invalidLlmChosenKeyIsRejected() {
        Agent badAgent = new SimpleAgent(new AgentConfig(
                "decide-agent", "sys",
                MockModelClient.scripted().respondText(
                        "{\"action\":\"wait_event\",\"event_key\":\"drop table users; --\"}"),
                null, 5));

        Workflow wf = Workflow.builder("bad-key-flow")
                .node(AgentNode.of("decide", badAgent))
                .node(DynamicSchedulerNode.of("wait", "decide"))
                .edge(Workflow.START, "decide")
                .edge("decide", "wait")
                .edge("wait", Workflow.END)
                .build();

        var result = runManager.start(wf, "input");
        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("rejected by policy"));
    }

    @Test
    void outOfRangeLlmDelayIsRejected() {
        Agent greedyAgent = new SimpleAgent(new AgentConfig(
                "decide-agent", "sys",
                MockModelClient.scripted().respondText(
                        "{\"action\":\"schedule\",\"delay_seconds\":999999999}"),
                null, 5));

        Workflow wf = Workflow.builder("bad-delay-flow")
                .node(AgentNode.of("decide", greedyAgent))
                .node(DynamicSchedulerNode.of("check", "decide"))
                .edge(Workflow.START, "decide")
                .edge("decide", "check")
                .edge("check", Workflow.END)
                .build();

        var result = runManager.start(wf, "input");
        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("out of allowed range"));
    }

    @Test
    void missingIntentFailsFast() {
        Workflow wf = Workflow.builder("no-intent-flow")
                .node(ActionNode.of("nothing", ctx -> "no intent written"))
                .node(DynamicSchedulerNode.of("wait", "decide"))   // "decide" never written
                .edge(Workflow.START, "nothing")
                .edge("nothing", "wait")
                .edge("wait", Workflow.END)
                .build();

        var result = runManager.start(wf, "input");
        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("No scheduling intent"));
    }

    // ============ LLM-driven task dispatch (composition) ============

    @Test
    void llmProducedSubTasksEnterQueue() {
        // The "LLM" outputs a plan; the taskProducer lambda parses it into tasks
        Agent planAgent = new SimpleAgent(new AgentConfig(
                "plan-agent", "sys",
                MockModelClient.scripted().respondText(
                        "{\"tasks\":[{\"input\":\"urgent-fix\",\"prio\":\"URGENT\"},{\"input\":\"normal-check\",\"prio\":\"NORMAL\"}]}"),
                null, 5));

        Workflow wf = Workflow.builder("llm-dispatch-flow")
                .node(AgentNode.of("plan", planAgent))
                .node(io.github.qwzhang01.agent.scheduler.nodes.DispatchTaskNode.of("dispatch", ctx -> {
                    try {
                        String json = String.valueOf(ctx.state().get("plan"));
                        var arr = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(json).get("tasks");
                        java.util.List<AsyncTask> tasks = new java.util.ArrayList<>();
                        for (var t : arr) {
                            tasks.add(AsyncTask.of(ctx.runId(), t.get("input").asText(),
                                    TaskPriority.valueOf(t.get("prio").asText()), "sub-wf"));
                        }
                        return tasks;
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }))
                .edge(Workflow.START, "plan")
                .edge("plan", "dispatch")
                .edge("dispatch", Workflow.END)
                .build();

        var result = runManager.start(wf, "fix and check");
        assertTrue(result.isSucceeded());
        assertEquals(2, scheduler.getTaskQueue().size());
        assertEquals(TaskPriority.URGENT, scheduler.pollNextTask().priority());
    }
}
