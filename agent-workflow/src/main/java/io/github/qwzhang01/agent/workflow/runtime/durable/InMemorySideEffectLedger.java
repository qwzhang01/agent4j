package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference in-memory {@link SideEffectLedger} (Stage 3.2). Same contract
 * as the file/JDBC backends; the crash tests exercise it through the
 * interface so the port keeps semantics identical.
 */
public final class InMemorySideEffectLedger implements SideEffectLedger {

    private final Map<String, Effect> effects = new ConcurrentHashMap<>();
    private final Map<String, List<Effect>> byRun = new ConcurrentHashMap<>();

    @Override
    public Effect record(Effect effect) {
        Effect existing = effects.putIfAbsent(effect.effectId(), effect);
        if (existing != null) {
            return existing; // crash-replay: original wins, no double count
        }
        byRun.computeIfAbsent(effect.runId(), k ->
                new java.util.concurrent.CopyOnWriteArrayList<>()).add(effect);
        return effect;
    }

    @Override
    public Optional<Effect> lookup(String runId, String nodeId) {
        return Optional.ofNullable(effects.get(Effect.idFor(runId, nodeId)));
    }

    @Override
    public Optional<Effect> lookup(String runId, String nodeId, String callHash) {
        return Optional.ofNullable(effects.get(Effect.idFor(runId, nodeId, callHash)));
    }

    @Override
    public List<Effect> effectsForRun(String runId) {
        return List.copyOf(byRun.getOrDefault(runId, List.of()));
    }

    @Override
    public List<Effect> allEffects() {
        return List.copyOf(effects.values());
    }
}
