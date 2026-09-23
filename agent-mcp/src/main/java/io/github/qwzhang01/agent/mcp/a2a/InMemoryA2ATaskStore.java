package io.github.qwzhang01.agent.mcp.a2a;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 *  in-memory {@link A2ATaskStore} — the default, and the reference
 * semantics for real implementations.
 * <p>
 * Same data as the old hard-coded map, but behind the interface: the server
 * no longer knows where tasks live. Lease entries carry a holder + expiry;
 * expiry is checked lazily on every lease operation (no background sweeper
 * thread for the in-memory case — a Redis impl would lean on TTL natively).
 */
public final class InMemoryA2ATaskStore implements A2ATaskStore {

    private final Map<String, StoredA2ATask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();

    private record Lease(String holderId, Instant expiresAt) {
        boolean isLive(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    @Override
    public void save(StoredA2ATask task) {
        tasks.put(task.taskId(), task);
    }

    @Override
    public Optional<StoredA2ATask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<StoredA2ATask> findByContext(String contextId) {
        List<StoredA2ATask> result = new ArrayList<>();
        for (StoredA2ATask task : tasks.values()) {
            if (contextId.equals(task.contextId())) {
                result.add(task);
            }
        }
        result.sort(Comparator.comparing(StoredA2ATask::updatedAt).reversed());
        return result;
    }

    @Override
    public boolean acquireLease(String taskId, String holderId, java.time.Duration ttl) {
        Instant now = Instant.now();
        AtomicReference<Boolean> acquired = new AtomicReference<>(false);
        leases.compute(taskId, (id, existing) -> {
            if (existing != null && existing.isLive(now) && !existing.holderId().equals(holderId)) {
                return existing; // someone else's live lease
            }
            acquired.set(true);
            return new Lease(holderId, now.plus(ttl));
        });
        return acquired.get();
    }

    @Override
    public boolean renewLease(String taskId, String holderId, java.time.Duration ttl) {
        Instant now = Instant.now();
        AtomicReference<Boolean> renewed = new AtomicReference<>(false);
        leases.compute(taskId, (id, existing) -> {
            if (existing == null || !existing.holderId().equals(holderId)) {
                return existing; // no lease, or someone else's
            }
            if (!existing.isLive(now)) {
                return null; // expired: drop the entry, deny the renewal (javadoc contract)
            }
            renewed.set(true);
            return new Lease(holderId, now.plus(ttl));
        });
        return renewed.get();
    }

    @Override
    public void releaseLease(String taskId, String holderId) {
        leases.computeIfPresent(taskId, (id, existing) ->
                existing.holderId().equals(holderId) ? null : existing);
    }

    @Override
    public int expireOlderThan(Instant cutoff) {
        int removed = 0;
        for (StoredA2ATask task : tasks.values()) {
            if (task.updatedAt().isBefore(cutoff)) {
                tasks.remove(task.taskId());
                leases.remove(task.taskId());
                removed++;
            }
        }
        return removed;
    }

    @Override
    public Optional<StoredA2ATask> findLiveByContext(String contextId) {
        for (StoredA2ATask task : tasks.values()) {
            if (contextId.equals(task.contextId()) && isLive(task.status())) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    private static boolean isLive(A2ATaskStatus status) {
        return status == A2ATaskStatus.SUBMITTED
                || status == A2ATaskStatus.WORKING
                || status == A2ATaskStatus.INPUT_REQUIRED;
    }

    /** Task count (tests / diagnostics). */
    public int size() {
        return tasks.size();
    }
}
