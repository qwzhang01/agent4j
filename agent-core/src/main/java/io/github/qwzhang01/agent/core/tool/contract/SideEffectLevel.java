package io.github.qwzhang01.agent.core.tool.contract;

/**
 * How much external state a tool can touch (Stage 2.1, harness roadmap).
 * <p>
 * The level feeds two defaults: the permission a secure assembly grants the
 * tool out of the box, and the audit posture (read-only tools need lighter
 * scrutiny than destructive ones). {@link #UNKNOWN} is the honest answer for
 * legacy tools that never declared their effects — a secure assembly treats
 * UNKNOWN as {@link #SIDE_EFFECT} (conservative, fail-safe).
 */
public enum SideEffectLevel {

    /** Pure computation, no state read or written (e.g. a math helper). */
    NONE,

    /** Reads external state but never mutates it (e.g. get_weather). */
    READ_ONLY,

    /** Mutates external state but reversibly or scoped (e.g. write_note). */
    SIDE_EFFECT,

    /** Irreversible or blast-radius-heavy (e.g. delete_file, send_email). */
    DESTRUCTIVE,

    /** Legacy tool that did not declare a level. Secure default: cautious. */
    UNKNOWN;

    /**
     * The conservative mapping used when a secure assembly derives a default
     * permission: read-shaped levels stay automatic, everything else asks.
     */
    public boolean isReadShaped() {
        return this == NONE || this == READ_ONLY;
    }
}
