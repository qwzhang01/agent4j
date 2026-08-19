package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Governance-enabled ToolExecutor (Stage 9 D1 - decorator pattern).
 * <p>
 * Wraps a delegate {@link ToolExecutor} (typically {@code DefaultToolExecutor})
 * and inserts the governance four-pack around each tool call:
 * <ol>
 *   <li>Permission check (AUTO / REQUIRES_APPROVAL / DENY)</li>
 *   <li>Rate limiting (optional)</li>
 *   <li>Approval (for REQUIRES_APPROVAL tools)</li>
 *   <li>Execution (via delegate)</li>
 *   <li>Result sanitization (injection defense)</li>
 *   <li>Audit logging (always, including denials)</li>
 * </ol>
 * <p>
 * When not configured (null policy / null approval / null sanitizer / null audit),
 * the corresponding step is skipped. This makes it possible to incrementally
 * enable governance features, and ensures backward compatibility with Stage 1-8
 * (which used plain DefaultToolExecutor with no governance).
 */
public class GovernedToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GovernedToolExecutor.class);

    private final ToolExecutor delegate;
    private final PermissionChecker permissionChecker;
    private final ToolApprovalService approvalService;      // nullable
    private final ResultSanitizer resultSanitizer;          // nullable
    private final AuditLogger auditLogger;                  // nullable
    private final RateLimiter rateLimiter;                  // nullable
    private final String runId;                             // nullable (null = not run-scoped)

    private GovernedToolExecutor(Builder builder) {
        this.delegate = builder.delegate;
        this.permissionChecker = builder.permissionChecker;
        this.approvalService = builder.approvalService;
        this.resultSanitizer = builder.resultSanitizer;
        this.auditLogger = builder.auditLogger;
        this.rateLimiter = builder.rateLimiter;
        this.runId = builder.runId;
    }

    @Override
    public String execute(ToolCall toolCall) {
        // ---- 1. Permission check ----
        if (permissionChecker != null) {
            ToolPermission perm = permissionChecker.check(toolCall.name());
            if (perm == ToolPermission.DENY) {
                String reason = "Tool '" + toolCall.name() + "' is denied by policy";
                log.warn("[Security] {}", reason);
                audit(AuditEvent.denied(runId, toolCall, reason));
                return "[DENIED] " + reason;
            }
            if (perm == ToolPermission.REQUIRES_APPROVAL) {
                // ---- 2. Approval ----
                if (approvalService == null) {
                    String reason = "Tool '" + toolCall.name() + "' requires approval but no approval service configured";
                    log.warn("[Security] {}", reason);
                    audit(AuditEvent.denied(runId, toolCall, reason));
                    return "[DENIED] " + reason;
                }
                boolean approved = approvalService.request(toolCall, runId);
                if (!approved) {
                    String reason = "Approval rejected for tool '" + toolCall.name() + "'";
                    log.info("[Security] {}", reason);
                    audit(AuditEvent.denied(runId, toolCall, reason));
                    return "[DENIED] " + reason;
                }
                audit(AuditEvent.approved(runId, toolCall));
            }
        }

        // ---- 3. Rate limiting ----
        if (rateLimiter != null && !rateLimiter.tryAcquire(toolCall.name())) {
            String reason = "Rate limit exceeded for tool '" + toolCall.name() + "'";
            log.warn("[Security] {}", reason);
            audit(AuditEvent.denied(runId, toolCall, reason));
            return "[RATE_LIMITED] " + reason;
        }

        // ---- 4. Execute via delegate ----
        long start = System.currentTimeMillis();
        String result;
        try {
            result = delegate.execute(toolCall);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("[Security] Tool '{}' failed: {}", toolCall.name(), error);
            audit(AuditEvent.failed(runId, toolCall, error, duration));
            throw e;
        }
        long duration = System.currentTimeMillis() - start;

        // ---- 5. Result sanitization (injection defense) ----
        if (resultSanitizer != null && result != null) {
            SanitizeResult sr = resultSanitizer.sanitize(result);
            if (sr.modified()) {
                log.info("[Security] Result sanitized for tool '{}': {}", toolCall.name(), sr.reason());
                audit(AuditEvent.sanitized(runId, toolCall, sr.sanitized(), sr.reason(), duration));
                return sr.sanitized();
            }
        }

        // ---- 6. Audit ----
        audit(AuditEvent.executed(runId, toolCall, result, duration));
        return result;
    }

    private void audit(AuditEvent event) {
        if (auditLogger != null) {
            auditLogger.log(event);
        }
    }

    // ============ Builder ============

    public static Builder builder(ToolExecutor delegate) {
        return new Builder(delegate);
    }

    public static final class Builder {
        private final ToolExecutor delegate;
        private PermissionChecker permissionChecker;
        private ToolApprovalService approvalService;
        private ResultSanitizer resultSanitizer;
        private AuditLogger auditLogger;
        private RateLimiter rateLimiter;
        private String runId;

        public Builder(ToolExecutor delegate) {
            this.delegate = delegate;
        }

        public Builder permissionChecker(PermissionChecker checker) {
            this.permissionChecker = checker;
            return this;
        }

        public Builder approvalService(ToolApprovalService service) {
            this.approvalService = service;
            return this;
        }

        public Builder resultSanitizer(ResultSanitizer sanitizer) {
            this.resultSanitizer = sanitizer;
            return this;
        }

        public Builder auditLogger(AuditLogger logger) {
            this.auditLogger = logger;
            return this;
        }

        public Builder rateLimiter(RateLimiter limiter) {
            this.rateLimiter = limiter;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public GovernedToolExecutor build() {
            return new GovernedToolExecutor(this);
        }
    }
}
