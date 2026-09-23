package io.github.qwzhang01.agent.core.redact;

import java.util.Objects;

/**
 * Redaction policy for a governed surface .
 * <p>
 * Answers one question per surface: when this surface is about to persist
 * or return text, which of the four representations does it keep?
 * <ul>
 *   <li><b>RAW</b> - the original bytes (forensic replay, encrypted stores)</li>
 *   <li><b>SUMMARY</b> - a truncated digest (120-char result summary)</li>
 *   <li><b>HASH</b> - a SHA-256 digest (correlation without content)</li>
 *   <li><b>MASKED</b> - the masked view (model-visible / export-visible)</li>
 * </ul>
 * A surface can keep several representations at once (raw in the forensic
 * store + masked in exports + hash in logs) - the policy is a set of flags,
 * not a single choice. What a policy forbids is what the surface must NOT
 * write anywhere: {@link #keepsRaw} false means the raw text never hits
 * disk on that surface, including "debug" side files.
 * <p>
 * The default masker is {@code null} = no masking (passthrough) so that
 * existing single-tenant deployments keep byte-for-byte behavior; a host
 * that turns on masking for one surface does not silently change another.
 */
public final class RedactionPolicy {

    private final boolean keepRaw;
    private final boolean keepSummary;
    private final boolean keepHash;
    private final boolean keepMasked;
    private final SecretMasker masker;

    private RedactionPolicy(boolean keepRaw, boolean keepSummary, boolean keepHash,
                            boolean keepMasked, SecretMasker masker) {
        this.keepRaw = keepRaw;
        this.keepSummary = keepSummary;
        this.keepHash = keepHash;
        this.keepMasked = keepMasked;
        this.masker = masker;
    }

    /** Raw plaintext may be persisted on this surface (forensic / encrypted stores). */
    public boolean keepsRaw() {
        return keepRaw;
    }

    /** A short summary may be persisted (audit summaries, dashboards). */
    public boolean keepsSummary() {
        return keepSummary;
    }

    /** A SHA-256 hash may be persisted (correlation without content). */
    public boolean keepsHash() {
        return keepHash;
    }

    /** The masked view is produced and persisted (model / export visibility). */
    public boolean keepsMasked() {
        return keepMasked;
    }

    /** The masker for this surface; {@code null} = passthrough (no masking). */
    public SecretMasker masker() {
        return masker;
    }

    /** Mask {@code text} per this policy; passthrough when no masker. */
    public String apply(String text) {
        return masker == null ? text : masker.mask(text);
    }

    /**
     * Permissive default: raw kept, nothing masked - identical to the
     * pre-Stage-5 behavior of every surface. Single-tenant local runs
     * keep this unless they opt into governance.
     */
    public static RedactionPolicy rawOnly() {
        return new RedactionPolicy(true, false, false, false, null);
    }

    /**
     * Export / replay / DPO posture: masked view only, raw never written.
     * The hash stays for correlation (same text across runs matches).
     */
    public static RedactionPolicy maskedOnly(SecretMasker masker) {
        Objects.requireNonNull(masker, "masker");
        return new RedactionPolicy(false, true, true, true, masker);
    }

    /**
     * Memory / audit posture: raw kept in the ledger (owner may view their
     * own data), masked view produced for every other consumer, hash for
     * log correlation.
     */
    public static RedactionPolicy rawPlusMasked(SecretMasker masker) {
        Objects.requireNonNull(masker, "masker");
        return new RedactionPolicy(true, true, true, true, masker);
    }

    /**
     * Log-only posture: no content at all, just hash + fixed-length summary.
     */
    public static RedactionPolicy hashOnly() {
        return new RedactionPolicy(false, true, true, false, null);
    }
}
