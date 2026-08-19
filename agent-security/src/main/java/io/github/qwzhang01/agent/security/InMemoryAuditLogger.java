package io.github.qwzhang01.agent.security;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of {@link AuditLogger} (Stage 9 v1).
 * <p>
 * Uses CopyOnWriteArrayList for thread-safe append + safe iteration.
 * Events are kept in insertion order. A secondary index by runId and
 * toolName allows efficient filtering without scanning the full list.
 */
public class InMemoryAuditLogger implements AuditLogger {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, List<AuditEvent>> byRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<AuditEvent>> byTool = new ConcurrentHashMap<>();

    @Override
    public void log(AuditEvent event) {
        events.add(event);
        if (event.runId() != null) {
            byRun.computeIfAbsent(event.runId(), k -> new CopyOnWriteArrayList<>()).add(event);
        }
        byTool.computeIfAbsent(event.toolName(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public List<AuditEvent> getAll() {
        return new ArrayList<>(events);
    }

    @Override
    public List<AuditEvent> getByRun(String runId) {
        return new ArrayList<>(byRun.getOrDefault(runId, List.of()));
    }

    @Override
    public List<AuditEvent> getByTool(String toolName) {
        return new ArrayList<>(byTool.getOrDefault(toolName, List.of()));
    }

    /**
     * Clear all events (for testing).
     */
    public void clear() {
        events.clear();
        byRun.clear();
        byTool.clear();
    }
}
