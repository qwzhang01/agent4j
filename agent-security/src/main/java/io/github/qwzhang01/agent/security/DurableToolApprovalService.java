package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.approval.ApprovalDecision;
import io.github.qwzhang01.agent.core.approval.ApprovalRequest;
import io.github.qwzhang01.agent.core.approval.ApprovalStatus;
import io.github.qwzhang01.agent.core.approval.ApprovalStore;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.contract.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Tool-layer {@link ToolApprovalService} backed by the durable
 * {@link ApprovalStore}. A PENDING row means the ReAct loop must pause
 * ({@code WAITING_APPROVAL}) instead of blocking a thread; after an
 * operator lands a decision, {@link io.github.qwzhang01.agent.core.agent.Agent#resume}
 * retries the same tool call and this service returns APPROVED or REJECTED.
 * <p>
 * Lives in agent-security (not workflow) so the tool executor can depend
 * on it without crossing the security↛workflow prohibition. Workflow's
 * {@code PersistentApprovalService} is the node-granularity twin on the
 * same store protocol.
 */
public final class DurableToolApprovalService implements ToolApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DurableToolApprovalService.class);

    private final ApprovalStore store;
    private final String riskLevel;
    private final long ttlMillis;

    public DurableToolApprovalService(ApprovalStore store, String riskLevel, long ttlMillis) {
        this.store = Objects.requireNonNull(store, "store");
        this.riskLevel = riskLevel == null ? "UNSPECIFIED" : riskLevel;
        this.ttlMillis = ttlMillis;
    }

    public DurableToolApprovalService(ApprovalStore store) {
        this(store, "UNSPECIFIED", 0);
    }

    @Override
    public boolean request(ToolCall toolCall, String runId) {
        return verdict(toolCall, runId) == Verdict.APPROVED;
    }

    @Override
    public Verdict verdict(ToolCall toolCall, String runId) {
        if (runId == null || runId.isBlank()) {
            log.warn("[Approval] refusing tool '{}' — no runId, cannot persist a decision",
                    toolCall.name());
            return Verdict.REJECTED;
        }
        String hash = callHash(toolCall);
        String approvalId = ApprovalRequest.idForToolCall(runId, hash);
        Optional<ApprovalRequest> existing = store.get(approvalId);
        if (existing.isEmpty()) {
            long now = System.currentTimeMillis();
            ApprovalRequest row = new ApprovalRequest(
                    approvalId, runId, toolCall.id() == null ? toolCall.name() : toolCall.id(),
                    hash, "tool:" + toolCall.name(), riskLevel,
                    "approve tool '" + toolCall.name() + "'",
                    ttlMillis <= 0 ? 0 : now + ttlMillis, now,
                    ApprovalStatus.PENDING, null, 0);
            store.submit(row);
            log.info("[Approval] submitted PENDING for tool '{}' runId={}", toolCall.name(), runId);
            return Verdict.PENDING;
        }
        ApprovalRequest row = existing.get();
        if (row.isOverdue(System.currentTimeMillis())) {
            store.expireOverdue(System.currentTimeMillis());
            row = store.get(approvalId).orElse(row);
        }
        return switch (row.status()) {
            case PENDING -> Verdict.PENDING;
            case APPROVED -> Verdict.APPROVED;
            case REJECTED, EXPIRED, REVOKED -> Verdict.REJECTED;
        };
    }

    /** Operator action: land APPROVED on the derived tool-call id. */
    public ApprovalRequest approve(String runId, ToolCall toolCall, String decidedBy, String reason) {
        return decide(runId, toolCall, ApprovalStatus.APPROVED, decidedBy, reason);
    }

    /** Operator action: land REJECTED on the derived tool-call id. */
    public ApprovalRequest reject(String runId, ToolCall toolCall, String decidedBy, String reason) {
        return decide(runId, toolCall, ApprovalStatus.REJECTED, decidedBy, reason);
    }

    public ApprovalStore store() {
        return store;
    }

    private ApprovalRequest decide(String runId, ToolCall toolCall, ApprovalStatus target,
                                   String decidedBy, String reason) {
        String hash = callHash(toolCall);
        String approvalId = ApprovalRequest.idForToolCall(runId, hash);
        ApprovalRequest row = store.get(approvalId)
                .orElseThrow(() -> new IllegalStateException("No approval request for " + approvalId));
        return store.decide(row, ApprovalDecision.of(decidedBy, reason, row.version()), target);
    }

    /**
     * Tool name rides in the hash so two REQUIRES_APPROVAL tools with the
     * same (or null) arguments in one run never share an approval row.
     */
    static String callHash(ToolCall toolCall) {
        return ToolResult.hashArguments(toolCall.name() + "\n" + toolCall.arguments());
    }
}
