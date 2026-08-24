package io.github.qwzhang01.agent.memory;

/**
 * Type of a memory entry.
 * <p>
 * Used by retrieval to filter and by policy to apply type-specific rules.
 */
public enum MemoryType {
    /**
     * User preference (e.g. "prefers dark mode", "allergic to peanuts").
     */
    PREFERENCE,
    /**
     * A factual statement (e.g. "user's timezone is UTC+8").
     */
    FACT,
    /**
     * An episodic event (e.g. "user asked for a refund on 2026-08-19").
     */
    EPISODE,
    /**
     * A compressed summary of prior conversation (produced by ContextCompressor).
     */
    SUMMARY,
    /**
     * An external event worth remembering (e.g. "PR #123 was merged").
     */
    EVENT,
    /**
     * Tenant knowledge base content (Stage 15): imported documents, FAQs, policies.
     * Unlike conversation-derived memories, knowledge entries are admin-ingested
     * (controlled import, not dialog sediment) and live in {@code tenant:*} scopes.
     */
    KNOWLEDGE
}
