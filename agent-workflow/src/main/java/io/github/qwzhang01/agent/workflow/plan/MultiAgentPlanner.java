package io.github.qwzhang01.agent.workflow.plan;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.run.RunContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-Agent Plan : fan a {@link Plan} of subtasks out to named
 * sub-agents, join their outputs back with explicit state, isolation,
 * budget and dedup semantics.
 * <p>
 * The four roadmap words, as contracts:
 * <ul>
 *   <li><b>subtask state</b>: every subtask's lifecycle is one entry in
 *       the returned {@link Result} map (agent name → per-task outcome),
 *       observable without touching any sub-agent's internals.</li>
 *   <li><b>context isolation</b>: each sub-agent runs on its OWN fresh
 *       {@link AgentState} (its conversation never sees sibling prompts or
 *       outputs), and — when a parent {@link RunContext} is present — on a
 *       child context derived per subtask ({@code deriveChild}), so
 *       identity/tenant/trace propagate while runIds stay distinct.</li>
 *   <li><b>budget propagation</b>: child contexts inherit the parent's
 *       deadline and cancellation signal; a sub-agent that exhausts the
 *       shared budget fails its own subtask (state recorded) without
 *       tearing down siblings mid-flight.</li>
 *   <li><b>result dedup</b>: when multiple subtasks resolve to the same
 *       output text, the join keeps ONE canonical entry per distinct
 *       output and records which tasks produced it ({@code Result.dedup}
 *       groups), so downstream steps consume a set, not a bag of echoes.</li>
 * </ul>
 * <p>
 * Not a new Runtime: sub-agents are the SAME {@link Agent} interface the
 * rest of the framework runs (handoffs, governance, guardrails inside
 * each); this class is orchestration over them, reusing their failure
 * taxonomy (a failed sub-agent records its {@code lastError} in the
 * subtask state — the unified classification and recovery protocol of the
 * runtime, not a new one).
 */
public final class MultiAgentPlanner {

    /** One subtask: which agent runs it and what it is asked to do. */
    public record Subtask(String taskId, String agentName, String instruction) {
        public Subtask {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId must not be blank");
            }
            if (agentName == null || agentName.isBlank()) {
                throw new IllegalArgumentException("agentName must not be blank");
            }
        }
    }

    /** Per-subtask outcome. */
    public record SubtaskResult(String taskId, String agentName, boolean success,
                                String output, String error) {
    }

    /** Join result: per-task outcomes plus the deduplicated output set. */
    public record Result(List<SubtaskResult> subtasks, Map<String, List<String>> dedup) {

        /** Distinct outputs (the join's canonical set), in first-seen order. */
        public Set<String> distinctOutputs() {
            return new LinkedHashSet<>(dedup.keySet());
        }

        /** Successful subtask count. */
        public long successCount() {
            return subtasks.stream().filter(SubtaskResult::success).count();
        }
    }

    private final Map<String, Agent> agentsByName;

    public MultiAgentPlanner(Map<String, Agent> agentsByName) {
        if (agentsByName == null || agentsByName.isEmpty()) {
            throw new IllegalArgumentException("agentsByName must not be empty");
        }
        this.agentsByName = Map.copyOf(agentsByName);
    }

    /**
     * Run subtasks sequentially-and-deterministically (v1 scope: the join
     * semantics, not the scheduling; parallel dispatch composes on top via
     * the workflow runtime's ParallelNode when needed).
     *
     * @param subtasks subtask list (execution order = list order)
     * @param parentCtx optional parent run context; each sub-agent gets a
     *                  child context (trace/tenant inherited, own runId)
     */
    public Result run(List<Subtask> subtasks, RunContext parentCtx) {
        List<SubtaskResult> results = new ArrayList<>();
        for (Subtask subtask : subtasks) {
            Agent agent = agentsByName.get(subtask.agentName());
            if (agent == null) {
                results.add(new SubtaskResult(subtask.taskId(), subtask.agentName(),
                        false, null, "no agent registered under name '" + subtask.agentName() + "'"));
                continue;
            }

            // Context isolation: fresh state per subtask — sibling prompts
            // and outputs are invisible by construction.
            AgentState scratch = new AgentState(subtask.instruction());
            String output;
            String error = null;
            try {
                if (parentCtx != null) {
                    // Budget propagation: child inherits deadline/cancel;
                    // own runId keeps subtask state auditable per task.
                    RunContext child = parentCtx.deriveChild(subtask.taskId());
                    output = agent.run(subtask.instruction(), scratch, child);
                } else {
                    output = agent.run(subtask.instruction(), scratch);
                }
            } catch (Exception e) {
                output = null;
                error = e.getMessage();
            }
            boolean success = scratch.getStatus() == io.github.qwzhang01.agent.core.agent.AgentState.Status.DONE
                    && error == null;
            if (!success && error == null) {
                error = scratch.getLastError() != null
                        ? scratch.getLastError()
                        : "subtask ended in state " + scratch.getStatus();
            }
            results.add(new SubtaskResult(subtask.taskId(), subtask.agentName(),
                    success, output, error));
        }

        // Result dedup: group task ids by identical output text. Identical
        // outputs collapse to one canonical entry (first-seen order kept).
        Map<String, List<String>> dedup = new LinkedHashMap<>();
        for (SubtaskResult r : results) {
            if (r.success() && r.output() != null) {
                dedup.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r.taskId());
            }
        }
        return new Result(List.copyOf(results), dedup);
    }
}
