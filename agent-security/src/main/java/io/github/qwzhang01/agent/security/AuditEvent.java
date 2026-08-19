package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Audit event record (Stage 9 D6).
 * <p>
 * One event is produced per tool call attempt, regardless of outcome.
 * Failed/denied calls are also audited - "who tried to do what but was blocked"
 * is itself a security event.
 *
 * @param eventId    unique event id
 * @param runId      the run that triggered this call (null if not run-scoped)
 * @param toolName   the tool being called
 * @param args       JSON arguments (may be truncated for storage)
 * @param result     execution result (may be truncated; null if not executed)
 * @param status     outcome: APPROVED / DENIED / EXECUTED / FAILED / SANITIZED
 * @param timestamp  when the event was recorded
 * @param durationMs execution duration (0 if not executed)
 * @param reason     denial reason / sanitization note (null if not applicable)
 */
public record AuditEvent(
        String eventId,
        String runId,
        String toolName,
        String args,
        String result,
        AuditStatus status,
        java.time.Instant timestamp,
        long durationMs,
        String reason
) {
    public enum AuditStatus {
        /** Approval was granted (pre-execution event for REQUIRES_APPROVAL tools). */
        APPROVED,
        /** Permission denied or approval rejected - tool NOT executed. */
        DENIED,
        /** Tool executed successfully. */
        EXECUTED,
        /** Tool execution threw an exception. */
        FAILED,
        /** Tool result was sanitized (injection defense triggered). */
        SANITIZED
    }

    // ============ Factory Methods ============

    public static AuditEvent denied(String runId, ToolCall toolCall, String reason) {
        return new AuditEvent(UUID.randomUUID().toString(), runId, toolCall.name(),
                truncate(toolCall.arguments() != null ? toolCall.arguments().toString() : "{}"),
                null, AuditStatus.DENIED, java.time.Instant.now(), 0, reason);
    }

    public static AuditEvent approved(String runId, ToolCall toolCall) {
        return new AuditEvent(UUID.randomUUID().toString(), runId, toolCall.name(),
                truncate(toolCall.arguments() != null ? toolCall.arguments().toString() : "{}"),
                null, AuditStatus.APPROVED, java.time.Instant.now(), 0, "approval granted");
    }

    public static AuditEvent executed(String runId, ToolCall toolCall, String result, long durationMs) {
        return new AuditEvent(UUID.randomUUID().toString(), runId, toolCall.name(),
                truncate(toolCall.arguments() != null ? toolCall.arguments().toString() : "{}"),
                truncate(result), AuditStatus.EXECUTED, java.time.Instant.now(), durationMs, null);
    }

    public static AuditEvent failed(String runId, ToolCall toolCall, String error, long durationMs) {
        return new AuditEvent(UUID.randomUUID().toString(), runId, toolCall.name(),
                truncate(toolCall.arguments() != null ? toolCall.arguments().toString() : "{}"),
                truncate(error), AuditStatus.FAILED, java.time.Instant.now(), durationMs, null);
    }

    public static AuditEvent sanitized(String runId, ToolCall toolCall, String result, String reason, long durationMs) {
        return new AuditEvent(UUID.randomUUID().toString(), runId, toolCall.name(),
                truncate(toolCall.arguments() != null ? toolCall.arguments().toString() : "{}"),
                truncate(result), AuditStatus.SANITIZED, java.time.Instant.now(), durationMs, reason);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) + "...[truncated]" : s;
    }
}
