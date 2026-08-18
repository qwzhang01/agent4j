package io.github.qwzhang01.agent.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The blackboard: the single shared mutable state of one workflow run.
 * <p>
 * Design decision (D3 in notes/architecture-stage-5.md): blackboard over
 * message passing because it is simple to inspect, snapshot-friendly
 * (Stage 6 Checkpoint = serialize this object) and observable.
 * <p>
 * Zones:
 * - input: written once at run() time (read-only by convention)
 * - variables: node outputs stored under node id; routing conditions read here
 * - trace: one StepRecord per executed node (Stage 14 trajectory source)
 * <p>
 * Thread-safe: parallel branches write under distinct keys.
 */
public class WorkflowState {

    // ============ Fields ============

    private final Object input;
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final List<StepRecord> trace = new CopyOnWriteArrayList<>();

    // ============ Constructors ============

    public WorkflowState(Object input) {
        this.input = input;
    }

    public static WorkflowState of(Object input) {
        return new WorkflowState(input);
    }

    /**
     * Reconstruct a blackboard from a checkpoint snapshot
     * ({@code FileCheckpointStore} / crash recovery).
     */
    public static WorkflowState restore(Object input, Map<String, Object> variables, List<StepRecord> trace) {
        WorkflowState state = new WorkflowState(input);
        if (variables != null) {
            variables.forEach(state::put);
        }
        if (trace != null) {
            trace.forEach(state::record);
        }
        return state;
    }

    // ============ Input Zone ============

    public Object getInput() {
        return input;
    }

    // ============ Variables Zone (blackboard) ============

    public Object get(String key) {
        return variables.get(key);
    }

    public void put(String key, Object value) {
        variables.put(key, value);
    }

    public Map<String, Object> getVariables() {
        return Map.copyOf(variables);
    }

    // ============ Trace Zone ============

    public void record(StepRecord record) {
        trace.add(record);
    }

    public List<StepRecord> getTrace() {
        return List.copyOf(trace);
    }
}
