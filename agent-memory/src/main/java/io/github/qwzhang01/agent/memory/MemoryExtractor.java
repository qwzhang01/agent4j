package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-side extractor: turns conversation messages into candidate memory entries
 * (Stage 8).
 * <p>
 * v1 uses rule-based extraction: scans user messages for preference-indicating
 * keywords. Explicit {@code save_memory} tool calls (Stage 8 M8.5) produce
 * entries directly and bypass this extractor.
 */
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    // v1 rule keywords (bilingual)
    private static final List<String> PREFERENCE_KEYWORDS = List.of(
            "记住", "我是", "我喜欢", "我不喜欢", "我偏好", "我习惯", "我对", "别",
            "always", "never", "prefer", "i am", "i like", "i don't like"
    );

    /**
     * Extract candidate memory entries from a conversation.
     *
     * @param messages       the conversation to scan
     * @param scope          the scope to store under
     * @param baseProvenance provenance template (actor + runId + at)
     * @return candidate entries (not yet policy-gated or stored)
     */
    public List<MemoryEntry> extract(List<ChatMessage> messages, String scope,
                                     MemoryProvenance baseProvenance) {
        List<MemoryEntry> candidates = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (msg.role() != ChatRole.USER || msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            String content = msg.content().trim();
            String matched = matchKeyword(content);
            if (matched != null) {
                String subject = deriveSubject(content);
                candidates.add(new MemoryEntry(
                        null, scope, MemoryType.PREFERENCE, subject, content,
                        0.7, baseProvenance, MemoryStatus.ACTIVE,
                        Instant.now(), null
                ));
                log.debug("Extracted candidate memory: subject={}, content={}", subject, content);
            }
        }
        return candidates;
    }

    /**
     * Full write flow: extract -> policy gate -> supersede -> store.
     *
     * @return number of entries actually stored
     */
    public int extractAndStore(List<ChatMessage> messages, String scope,
                               MemoryProvenance provenance, MemoryPolicy policy,
                               MemoryStore store) {
        List<MemoryEntry> candidates = extract(messages, scope, provenance);
        int stored = 0;
        for (MemoryEntry candidate : candidates) {
            if (!policy.shouldStore(candidate, store)) {
                log.debug("Policy rejected candidate: {}", candidate.subject());
                continue;
            }
            // Gate 3: supersede existing ACTIVE entry with the same subject but different content
            if (policy.shouldSupersede(candidate, store)) {
                store.findActiveBySubject(candidate.scope(), candidate.subject())
                        .ifPresent(old -> {
                            store.update(old.withStatus(MemoryStatus.SUPERSEDED));
                            log.debug("Superseded old entry {} for subject {}", old.id(), old.subject());
                        });
            }
            // Apply scope-based default status (channel -> PENDING_REVIEW, Stage 8 D6 gate 2)
            MemoryStatus defaultStatus = policy.defaultStatusForScope(candidate.scope());
            MemoryEntry toStore = candidate.withStatus(defaultStatus);
            store.write(toStore);
            stored++;
        }
        log.info("Extracted {} candidates, stored {}", candidates.size(), stored);
        return stored;
    }

    private String matchKeyword(String content) {
        String lower = content.toLowerCase();
        for (String kw : PREFERENCE_KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) {
                return kw;
            }
        }
        return null;
    }

    /**
     * v1 subject derivation: first 20 chars of content.
     * Same content -> same subject (frequency control works).
     * Different content -> different subject (no false supersede).
     */
    private String deriveSubject(String content) {
        return content.length() <= 20 ? content : content.substring(0, 20);
    }
}
