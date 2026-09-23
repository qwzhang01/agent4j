package io.github.qwzhang01.agent.memory.ranking;

import io.github.qwzhang01.agent.memory.MemoryEntry;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Default ranking strategy: importance-score + query-relevance boost.
 *
 * <p>When a {@code query} is provided the effective sort key is:
 * <pre>
 *   score(e) = e.importance + QUERY_BOOST_WEIGHT * queryRelevance(e, query)
 * </pre>
 * A full token match (relevance = 1.0) can promote a low-importance entry above a
 * mid-importance non-matching one, but cannot displace entries whose importance exceeds
 * {@code 1 - QUERY_BOOST_WEIGHT}.
 *
 * <p>When {@code query} is {@code null} or blank, ranking degrades gracefully to
 * importance-then-recency, preserving backward compatibility.
 */
public final class ImportanceRankingStrategy implements RankingStrategy {

    /**
     * Weight applied to query relevance on top of base importance.
     * Package-visible so tests can reference the exact constant.
     */
    static final double QUERY_BOOST_WEIGHT = 0.5;

    private static final Comparator<MemoryEntry> BY_IMPORTANCE_THEN_RECENCY =
            Comparator.comparingDouble(MemoryEntry::importance).reversed()
                    .thenComparing(MemoryEntry::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    @Override
    public List<MemoryEntry> rank(List<MemoryEntry> candidates, String query) {
        return candidates.stream()
                .sorted(buildComparator(query))
                .toList();
    }

    /**
     * Relevance of a memory entry to a user query, in {@code [0.0, 1.0]}.
     *
     * <p>Default: token-based overlap between the query and the entry's
     * {@code subject + content}. Tokens are split on whitespace and common
     * punctuation; CJK bigrams are also generated so that short Chinese phrases
     * match partial content.
     *
     * <p>Override in a subclass to replace with embedding cosine similarity.
     *
     * @param entry active memory entry (never null when called from this class)
     * @param query non-null, non-blank query string
     */
    protected double queryRelevance(MemoryEntry entry, String query) {
        Set<String> tokens = extractQueryTokens(query);
        if (tokens.isEmpty()) return 0.0;
        String haystack = lower(entry.subject()) + " " + lower(entry.content());
        long matched = tokens.stream().filter(haystack::contains).count();
        return (double) matched / tokens.size();
    }

    private Comparator<MemoryEntry> buildComparator(String query) {
        if (query == null || query.isBlank()) {
            return BY_IMPORTANCE_THEN_RECENCY;
        }
        return Comparator.comparingDouble(
                        (MemoryEntry e) -> e.importance() + QUERY_BOOST_WEIGHT * queryRelevance(e, query))
                .reversed()
                .thenComparing(MemoryEntry::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /**
     * Extracts a deduplicated token set from a query string.
     * <ul>
     *   <li>Word tokens: split on whitespace and common punctuation; keep segments ≥ 2 chars.</li>
     *   <li>CJK bigrams: every consecutive CJK pair improves recall for Chinese phrases
     *       that lack whitespace delimiters.</li>
     * </ul>
     */
    private static Set<String> extractQueryTokens(String query) {
        Set<String> tokens = new HashSet<>();
        for (String word : query.split("[\\s，。！？、,.!?;；:：]+")) {
            if (!word.isBlank() && word.length() >= 2) {
                tokens.add(word.toLowerCase());
            }
        }
        for (int i = 0; i < query.length() - 1; i++) {
            char a = query.charAt(i);
            char b = query.charAt(i + 1);
            if (isCjk(a) && isCjk(b)) {
                tokens.add(String.valueOf(a) + b);
            }
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
