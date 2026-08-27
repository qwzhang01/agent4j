package io.github.qwzhang01.agent.chat.context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keyword and/or regex trigger for a lore entry.
 * <p>
 * Keywords are case-insensitive substrings. Regex uses the host-compiled
 * {@link Pattern} as-is (flags included). Either kind of hit is enough.
 * The engine does not own a keyword vocabulary.
 */
public final class LoreTrigger {

    private final List<String> keywords;
    private final Pattern pattern;

    private LoreTrigger(List<String> keywords, Pattern pattern) {
        this.keywords = keywords;
        this.pattern = pattern;
    }

    public static LoreTrigger keywords(String... keys) {
        return of(keys == null ? List.of() : Arrays.asList(keys), null);
    }

    public static LoreTrigger keywords(List<String> keys) {
        return of(keys, null);
    }

    public static LoreTrigger regex(String regex) {
        if (regex == null || regex.isBlank()) {
            throw new IllegalArgumentException("regex must not be blank");
        }
        return of(List.of(), Pattern.compile(regex));
    }

    public static LoreTrigger regex(Pattern pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("pattern must not be null");
        }
        return of(List.of(), pattern);
    }

    /**
     * Keyword hits <em>or</em> regex hit. At least one side must be present.
     */
    public static LoreTrigger of(List<String> keywords, Pattern pattern) {
        List<String> cleaned = copyKeywords(keywords);
        if (cleaned.isEmpty() && pattern == null) {
            throw new IllegalArgumentException("at least one keyword or a pattern is required");
        }
        return new LoreTrigger(cleaned, pattern);
    }

    public List<String> keywords() {
        return keywords;
    }

    public Pattern pattern() {
        return pattern;
    }

    public boolean matches(String text) {
        String haystack = text == null ? "" : text;
        if (!keywords.isEmpty()) {
            String folded = haystack.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (folded.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return pattern != null && pattern.matcher(haystack).find();
    }

    private static List<String> copyKeywords(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                cleaned.add(key.trim());
            }
        }
        return cleaned.isEmpty() ? List.of() : List.copyOf(cleaned);
    }
}
