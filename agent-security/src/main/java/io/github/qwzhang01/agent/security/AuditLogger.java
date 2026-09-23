package io.github.qwzhang01.agent.security;

import java.util.List;

/**
 * Audit logger interface (D6).
 * <p>
 * Records every tool call attempt as an {@link AuditEvent}, including
 * denied / rejected calls (not just successful ones).
 * <p>
 * v1 implementation: {@link InMemoryAuditLogger}. The interface allows
 * a persistent backend (DB / file / SIEM) to be added in .
 */
public interface AuditLogger {

    void log(AuditEvent event);

    /**
     * Retrieve all logged events (for inspection / testing).
     */
    List<AuditEvent> getAll();

    /**
     * Retrieve events for a specific run.
     */
    List<AuditEvent> getByRun(String runId);

    /**
     * Retrieve events for a specific tool.
     */
    List<AuditEvent> getByTool(String toolName);
}
