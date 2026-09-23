package io.github.qwzhang01.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 *  audit record for one governed memory access.
 * <p>
 * Answers the roadmap question "谁何时查了什么 scope、返回了多少条":
 * every read/write through {@link MemoryGovernance} emits one record.
 * The record itself carries no memory content — only shape metadata
 * (scopes, count, purpose) — so the audit trail never becomes a second
 * copy of sensitive data. Records are immutable facts for audit sinks
 * (host appends them to its own log store); this class does no I/O.
 *
 * @param operation READ or WRITE, the governed operation performed
 * @param tenantId tenant the acting identity belongs to (may be null: unauthenticated contexts)
 * @param userId acting user id (null for system actors)
 * @param runId the run that performed the access, for trace correlation
 * @param scopes scope whitelist actually used for the access (never null)
 * @param purpose declared purpose of the access (never null/blank)
 * @param resultCount how many entries the query returned (WRITE: entries written)
 * @param masked whether the consumer received masked content (RedactionPolicy applied)
 * @param at when the access happened
 */
public record MemoryAccessAuditRecord(
        Operation operation,
        String tenantId,
        String userId,
        String runId,
        List<String> scopes,
        String purpose,
        int resultCount,
        boolean masked,
        Instant at
) {

    /** The governed operation kinds. */
    public enum Operation {
        READ, WRITE
    }

    public MemoryAccessAuditRecord {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(scopes, "scopes");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(at, "at");
        if (purpose.isBlank()) {
            throw new IllegalArgumentException("purpose must not be blank");
        }
        if (resultCount < 0) {
            throw new IllegalArgumentException("resultCount must not be negative");
        }
        scopes = List.copyOf(scopes);
    }

    /**
     * Factory for a READ audit record.
     */
    public static MemoryAccessAuditRecord read(String tenantId, String userId, String runId,
                                               List<String> scopes, String purpose,
                                               int resultCount, boolean masked, Instant at) {
        return new MemoryAccessAuditRecord(Operation.READ, tenantId, userId, runId,
                scopes, purpose, resultCount, masked, at);
    }

    /**
     * Factory for a WRITE audit record.
     */
    public static MemoryAccessAuditRecord write(String tenantId, String userId, String runId,
                                                List<String> scopes, String purpose,
                                                int resultCount, Instant at) {
        return new MemoryAccessAuditRecord(Operation.WRITE, tenantId, userId, runId,
                scopes, purpose, resultCount, false, at);
    }
}
