package io.github.qwzhang01.agent.core.agent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Record of one context-trim decision (Stage 5.1).
 * <p>
 * Every time {@link ContextWindowEnforcer} drops messages to fit the history
 * budget, one {@code ContextTrimRecord} is produced: which trim source fired
 * (enforcer / handoff filter / compaction), how many messages were dropped,
 * the estimated token count before and after, and why. Hosts aggregate these
 * into per-run telemetry so "who got cut, when, at what cost" is auditable -
 * the roadmap's "裁剪原因、被裁剪来源、前后 token/字符数量" made queryable.
 * <p>
 * The record is deliberately attachable from multiple trim sites: the
 * enforcer's per-turn enforcement and {@link HandoffInputFilter}'s hop trim
 * both construct one via {@link #of}. Compaction ({@code ContextCompressor})
 * does not produce a record today - its archive path already keeps the
 * original text and fires its own log line; wiring it here is a v2 follow-up.
 */
public record ContextTrimRecord(
        String agentName,
        TrimSource source,
        int messagesBefore,
        int messagesAfter,
        int tokensBefore,
        int tokensAfter,
        int historyBudget,
        Instant at
) {

    public ContextTrimRecord {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(at, "at");
        if (messagesBefore < 0 || messagesAfter < 0 || messagesAfter > messagesBefore) {
            throw new IllegalArgumentException(
                    "message counts must satisfy 0 <= after <= before, got "
                            + messagesAfter + " > " + messagesBefore);
        }
        if (tokensBefore < 0 || tokensAfter < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    /** Messages dropped by this trim. */
    public int messagesDropped() {
        return messagesBefore - messagesAfter;
    }

    /** Estimated tokens reclaimed by this trim. */
    public int tokensReclaimed() {
        return tokensBefore - tokensAfter;
    }

    /** Was anything actually dropped. */
    public boolean didTrim() {
        return messagesAfter != messagesBefore;
    }

    public static ContextTrimRecord of(String agentName, TrimSource source,
                                       int messagesBefore, int messagesAfter,
                                       int tokensBefore, int tokensAfter,
                                       int historyBudget) {
        return new ContextTrimRecord(agentName, source, messagesBefore, messagesAfter,
                tokensBefore, tokensAfter, historyBudget, Instant.now());
    }

    /** Which trim site produced this record. */
    public enum TrimSource {
        /** Per-turn enforcement by {@link ContextWindowEnforcer}. */
        ENFORCER,
        /** Handoff hop trim by {@link HandoffInputFilter#withinBudget}. */
        HANDOFF_FILTER,
        /** Pi-style compaction by {@code ContextCompressor} (v2 hook). */
        COMPACTION
    }
}
