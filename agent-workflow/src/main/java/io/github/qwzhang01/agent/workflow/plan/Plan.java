package io.github.qwzhang01.agent.workflow.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A structured, validated, versioned execution plan (
 * Planner/Executor).
 * <p>
 * A {@code Plan} is a linear list of {@link Step steps} with explicit
 * dependencies. Validation enforces structural invariants at construction:
 * <ul>
 *   <li>every {@code after} reference names an existing step id;</li>
 *   <li>no cycles (topological order must exist);</li>
 *   <li>ids are unique and non-blank;</li>
 *   <li>at least one step exists.</li>
 * </ul>
 * <p>
 * Versioning: {@link #planVersion} increments on every structural
 * mutation (adding/removing steps, editing a step's command or deps).
 * Executors that checkpoint a plan can detect a changed plan on resume
 * ({@code planVersion != checkpoint.planVersion} → plan drift, surface
 * to the caller instead of silently executing a different plan).
 * <p>
 * Recovery contract: a plan plus a completed-step set is enough to resume:
 * {@link #readySteps(Set)} returns the steps whose dependencies are all
 * satisfied and which are not yet done. Executors replay from there.
 */
public final class Plan {

    /** A single plan step: an id, a natural-language objective, and deps. */
    public record Step(String id, String objective, List<String> after) {
        public Step {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("step id must not be blank");
            }
            after = after == null ? List.of() : List.copyOf(after);
        }

        /** Convenience: a step with no dependencies. */
        public static Step of(String id, String objective) {
            return new Step(id, objective, List.of());
        }
    }

    private final String planId;
    private final List<Step> steps;
    private int planVersion = 1;

    private Plan(String planId, List<Step> steps) {
        this(planId, steps, 1);
    }

    private Plan(String planId, List<Step> steps, int planVersion) {
        this.planId = planId;
        this.steps = steps;
        this.planVersion = planVersion;
        validate(steps);
    }

    /**
     * Build a plan from steps, preserving declaration order (which must be
     * a valid topological order — validated).
     */
    public static Plan of(String planId, List<Step> steps) {
        return new Plan(planId, new ArrayList<>(steps), 1);
    }

    public String planId() {
        return planId;
    }

    public int planVersion() {
        return planVersion;
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    /**
     * Structural mutation: rebuild this plan with new steps. The returned
     * plan carries {@code planVersion + 1} — versioning that actually
     * increments (the class javadoc promised this; before this method the
     * version was always 1, so drift was undetectable). A resumed
     * executor compares {@code planVersion} against its checkpoint and
     * refuses to run a drifted plan.
     */
    public Plan rebuildWith(List<Step> newSteps) {
        return new Plan(this.planId, new ArrayList<>(newSteps), this.planVersion + 1);
    }

    /**
     * Structural validation: unique non-blank ids, deps reference existing
     * steps, no cycles. Declaration order must be topological (a step may
     * only depend on EARLIER steps).
     */
    private static void validate(List<Step> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("plan must have at least one step");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Step step : steps) {
            // Deps are checked BEFORE this step joins `seen`, so a self
            // reference (a step depending on itself) is rejected here too.
            for (String dep : step.after()) {
                if (!seen.contains(dep)) {
                    throw new IllegalArgumentException(
                            "step '" + step.id() + "' depends on '" + dep
                                    + "' which is not an earlier step (unknown, self, or cycle)");
                }
            }
            if (!seen.add(step.id())) {
                throw new IllegalArgumentException("duplicate step id: " + step.id());
            }
        }
    }

    /**
     * Steps whose dependencies are all in {@code completed} and which are
     * not themselves completed: the executor's resume frontier.
     */
    public List<Step> readySteps(Set<String> completed) {
        List<Step> ready = new ArrayList<>();
        for (Step step : steps) {
            if (completed.contains(step.id())) {
                continue;
            }
            if (completed.containsAll(step.after())) {
                ready.add(step);
            }
        }
        return ready;
    }

    /**
     * All step ids (for checkpoint snapshots).
     */
    public Set<String> stepIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Step s : steps) {
            ids.add(s.id());
        }
        return ids;
    }

    @Override
    public String toString() {
        return "Plan[" + planId + " v" + planVersion + ", " + steps.size() + " steps]";
    }
}
