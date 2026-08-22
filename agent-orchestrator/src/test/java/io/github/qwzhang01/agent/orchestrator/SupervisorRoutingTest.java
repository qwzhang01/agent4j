package io.github.qwzhang01.agent.orchestrator;

import io.github.qwzhang01.agent.mcp.a2a.AgentCard;
import io.github.qwzhang01.agent.mcp.a2a.InProcessA2AClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11 M11.4 tests: skill-based routing on the supervisor (D7).
 */
class SupervisorRoutingTest {

    private static AgentCard testCard(String name, String... skills) {
        return new AgentCard(name, "test " + name, List.of(skills), "internal:" + name, "1.0");
    }

    private static AgentWorker worker(String name, String output, String... skills) {
        return new AgentWorker() {
            @Override public String name() { return name; }
            @Override public AgentCard card() { return testCard(name, skills); }
            @Override public WorkerResult execute(WorkerTask task) {
                return WorkerResult.success(task, output, 1, 1, 0);
            }
        };
    }

    @Test
    void dispatchBySkill_routesToTheMatchingWorker() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(worker("researcher", "research result", "research"));
            supervisor.register(worker("reviewer", "review result", "review"));

            WorkerResult result = supervisor.dispatchBySkill("review", "check this");

            assertTrue(result.success());
            assertEquals("review result", result.output());
            assertEquals("reviewer", result.workerName());
        }
    }

    @Test
    void dispatchBySkill_registrationOrderWinsOnMultipleMatches() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(worker("first", "first wins", "research"));
            supervisor.register(worker("second", "second loses", "research"));

            WorkerResult result = supervisor.dispatchBySkill("research", "x");

            assertEquals("first", result.workerName());  // deterministic: registration order
        }
    }

    @Test
    void dispatchBySkill_noMatch_failClosedWithAvailableSkills() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(worker("researcher", "r", "research"));

            WorkerResult result = supervisor.dispatchBySkill("cooking", "x");

            assertFalse(result.success());
            assertTrue(result.error().contains("no worker with skill 'cooking'"));
            assertTrue(result.error().contains("research"));  // lists what IS available
        }
    }

    @Test
    void dispatchBySkill_promptVariant_wrapsPayload() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(worker("w", "prompt handled", "echo"));

            WorkerResult result = supervisor.dispatchBySkill("echo", "hello routing");

            assertTrue(result.success());
            assertEquals("prompt handled", result.output());
        }
    }

    @Test
    void dispatchBySkill_externalWorker_viaA2A() {
        // routing treats external workers EXACTLY like internal ones (D1 payoff)
        InProcessA2AClient a2a = new InProcessA2AClient()
                .registerAgent("remote", new io.github.qwzhang01.agent.core.agent.Agent() {
                    @Override public String run(String in) { return "remote answer"; }
                    @Override public String run(String in, io.github.qwzhang01.agent.core.agent.AgentState s) {
                        return "remote answer";
                    }
                    @Override public io.github.qwzhang01.agent.core.agent.AgentConfig getConfig() { return null; }
                }, "translation");

        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(ExternalAgentWorker.of("remote", a2a, "translation"));

            WorkerResult result = supervisor.dispatchBySkill("translation", "translate this");

            assertTrue(result.success());
            assertEquals("remote answer", result.output());
        }
    }

    @Test
    void findWorkerBySkill_presentAndEmpty() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(worker("r", "x", "research"));

            Optional<AgentWorker> found = supervisor.findWorkerBySkill("research");
            Optional<AgentWorker> missing = supervisor.findWorkerBySkill("golf");

            assertTrue(found.isPresent());
            assertEquals("r", found.get().name());
            assertTrue(missing.isEmpty());
        }
    }
}
