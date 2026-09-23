package io.github.qwzhang01.agent.core.tool.contract;

import io.github.qwzhang01.agent.core.run.FailureKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Unified tool-result envelope (, harness roadmap).
 * <p>
 * Today every tool boundary collapses its outcome into a free string —
 * a denied call, a timeout and a business rejection are all
 * "Tool execution failed" from the loop's perspective. This record is the
 * typed envelope that separates them. The loop's model-visible rendering
 * stays a String (the model cannot consume an enum); the envelope rides
 * alongside for audit, metrics and the event pipeline.
 * <p>
 * Model-visible vs audit-visible : {@link #modelVisibleText}
 * is what goes back to the model — bracket-tagged, short, no stack traces;
 * the envelope itself carries the raw error classification, argument hash,
 * result size and redaction state for audits. The raw result string never
 * enters the model path when the call did not succeed.
 *
 * @param outcome the classified outcome
 * @param modelVisibleText what the model sees (never null)
 * @param failureKind core failure taxonomy anchor (null unless a failure)
 * @param rawError tool-thrown error text (null unless a failure)
 * @param argsHash SHA-256 prefix of the arguments (recorded, not raw args)
 * @param resultBytes size of the successful result in bytes (0 otherwise)
 * @param resultSummary first 120 chars of the successful result
 * @param redacted true when a sanitizer rewrote the result
 */
public record ToolResult(
        Outcome outcome,
        String modelVisibleText,
        FailureKind failureKind,
        String rawError,
        String argsHash,
        int resultBytes,
        String resultSummary,
        boolean redacted) {

    /** Classified outcome — the vocabulary every boundary must speak. */
    public enum Outcome {
        /** Tool ran, returned a result. */
        SUCCESS,
        /** Tool refused on business grounds (e.g. input semantically wrong). */
        BUSINESS_REJECTED,
        /** Tool threw / infrastructure failed. */
        SYSTEM_FAILURE,
        /** Per-call timeout expired. */
        TIMEOUT,
        /** Run was cancelled while executing. */
        CANCELLED,
        /** Result was rewritten by a sanitizer before the model saw it. */
        SANITIZED
    }

    public static ToolResult success(String result) {
        Objects.requireNonNull(result, "result must not be null");
        String summary = result.length() > 120 ? result.substring(0, 120) : result;
        return new ToolResult(Outcome.SUCCESS, result, null, null, null,
                result.getBytes(StandardCharsets.UTF_8).length, summary, false);
    }

    public static ToolResult sanitized(String sanitizedResult) {
        Objects.requireNonNull(sanitizedResult);
        String summary = sanitizedResult.length() > 120
                ? sanitizedResult.substring(0, 120) : sanitizedResult;
        return new ToolResult(Outcome.SANITIZED, sanitizedResult, null, null, null,
                sanitizedResult.getBytes(StandardCharsets.UTF_8).length, summary, true);
    }

    public static ToolResult businessRejected(String reason, String argsHash) {
        return new ToolResult(Outcome.BUSINESS_REJECTED,
                "[TOOL_REJECTED] " + reason,
                FailureKind.TOOL_FAILURE, reason, argsHash, 0, null, false);
    }

    public static ToolResult systemFailure(String error, String argsHash) {
        return new ToolResult(Outcome.SYSTEM_FAILURE,
                "[ERROR] Tool execution failed: " + error,
                FailureKind.TOOL_FAILURE, error, argsHash, 0, null, false);
    }

    public static ToolResult timeout(String detail, String argsHash) {
        return new ToolResult(Outcome.TIMEOUT,
                "[TIMEOUT] Tool execution timed out" + (detail != null && !detail.isBlank()
                        ? ": " + detail : ""),
                FailureKind.TIMEOUT, detail, argsHash, 0, null, false);
    }

    public static ToolResult cancelled(String argsHash) {
        return new ToolResult(Outcome.CANCELLED,
                "[CANCELLED] Tool execution cancelled",
                FailureKind.CANCELLED, null, argsHash, 0, null, false);
    }

    /** True when the call never produced a model-consumable result. */
    public boolean isFailure() {
        return outcome == Outcome.BUSINESS_REJECTED
                || outcome == Outcome.SYSTEM_FAILURE
                || outcome == Outcome.TIMEOUT;
    }

    /** SHA-256 prefix (16 hex chars) of the raw argument JSON, for audits. */
    public static String hashArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawArguments.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
