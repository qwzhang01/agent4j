package io.github.qwzhang01.agent.memory.extract;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryExtractor;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based {@link MemoryExtractor}: scans USER messages for generic
 * preference-indicating keywords. Does not assign business-specific subjects
 * (subject is the first 20 characters of the matched line).
 * <p>
 * Explicit {@code save_memory} tool calls produce entries directly and bypass
 * this extractor.
 */
public class KeywordMemoryExtractor implements MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(KeywordMemoryExtractor.class);

    private static final List<String> PREFERENCE_KEYWORDS = List.of(
            "记住", "我是", "我喜欢", "我不喜欢", "我偏好", "我习惯", "我对", "别",
            "always", "never", "prefer", "i am", "i like", "i don't like"
    );

    @Override
    public List<MemoryEntry> extract(List<ChatMessage> messages, String scope,
                                     MemoryProvenance baseProvenance) {
        List<MemoryEntry> candidates = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (msg.role() != ChatRole.USER || msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            String content = msg.content().trim();
            if (matchKeyword(content) != null) {
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

    private static String matchKeyword(String content) {
        String lower = content.toLowerCase();
        for (String kw : PREFERENCE_KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) {
                return kw;
            }
        }
        return null;
    }

    /**
     * First 20 chars of content. Same line → same subject (dedup).
     * Different line → different subject (no false supersede).
     */
    private static String deriveSubject(String content) {
        return content.length() <= 20 ? content : content.substring(0, 20);
    }
}
