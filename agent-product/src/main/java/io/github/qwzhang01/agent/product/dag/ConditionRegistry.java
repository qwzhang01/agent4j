package io.github.qwzhang01.agent.product.dag;

import io.github.qwzhang01.agent.workflow.WorkflowState;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Name &lt;-&gt; predicate registry for edge conditions (Stage 13 M13.5, D5).
 * <p>
 * Workflow edge conditions are Java lambdas - invisible to any serializer.
 * The registry is the D1 pattern applied to predicates: the DAG stores the
 * NAME, the registry stores the IMPLEMENTATION. Export resolves
 * predicate&nbsp;-&gt;&nbsp;name (fail-fast on strangers); import resolves
 * name&nbsp;-&gt;&nbsp;predicate.
 * <p>
 * Identity semantics: two lambdas with equal behavior are still different
 * objects - registration is per-instance, by design (a name maps to THE
 * predicate someone registered, not to any equal one).
 */
public final class ConditionRegistry {

    private final Map<String, Predicate<WorkflowState>> byName = new LinkedHashMap<>();
    private final Map<Predicate<WorkflowState>, String> nameOf = new IdentityHashMap<>();

    /**
     * Register a condition under a name.
     *
     * @throws IllegalArgumentException on duplicate name or predicate
     */
    public ConditionRegistry register(String name, Predicate<WorkflowState> predicate) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("condition name must not be blank");
        }
        Objects.requireNonNull(predicate, "predicate must not be null");
        if (byName.containsKey(name)) {
            throw new IllegalArgumentException("Condition '" + name + "' is already registered");
        }
        if (nameOf.containsKey(predicate)) {
            throw new IllegalArgumentException(
                    "This predicate is already registered as '" + nameOf.get(predicate) + "'");
        }
        byName.put(name, predicate);
        nameOf.put(predicate, name);
        return this;
    }

    /**
     * The predicate registered under a name, null if absent.
     */
    public Predicate<WorkflowState> predicateOf(String name) {
        return byName.get(name);
    }

    /**
     * The name a predicate was registered under, null if it is a stranger.
     */
    public String nameOf(Predicate<WorkflowState> predicate) {
        return nameOf.get(predicate);
    }

    public Set<String> names() {
        return new LinkedHashSet<>(byName.keySet());
    }
}
