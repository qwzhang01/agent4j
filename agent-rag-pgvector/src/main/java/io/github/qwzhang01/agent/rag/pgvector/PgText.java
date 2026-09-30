package io.github.qwzhang01.agent.rag.pgvector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Literal builders for PostgreSQL full-text types and LIKE patterns. Values are passed as bind
 * parameters and cast ({@code ?::tsvector}, {@code ?::tsquery}), so the only syntax to get right is
 * the type's own input format; lexemes are quoted and escaped, never parsed by a text-search parser.
 */
final class PgText {

    /** tsvector/tsquery reject lexemes of 2 KB or more (MAXSTRLEN in PostgreSQL). */
    static final int MAX_LEXEME_BYTES = 2046;
    /** Largest tsvector position; PostgreSQL clamps bigger ones to it. */
    static final int MAX_POSITION = 16383;
    /** tsvector keeps at most 256 positions per lexeme. */
    static final int MAX_POSITIONS_PER_LEXEME = 256;

    private PgText() {
    }

    /**
     * tsvector literal of {@code tokens} with 1-based positions, e.g. {@code 'a':1,3 'b':2}.
     * Tokens beyond {@link #MAX_POSITION}, oversized or containing NUL are skipped.
     */
    static String tsvector(List<String> tokens) {
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        int limit = Math.min(tokens.size(), MAX_POSITION);
        for (int i = 0; i < limit; i++) {
            String token = tokens.get(i);
            if (!usable(token)) {
                continue;
            }
            List<Integer> list = positions.computeIfAbsent(token, t -> new ArrayList<>());
            if (list.size() < MAX_POSITIONS_PER_LEXEME) {
                list.add(i + 1);
            }
        }
        StringBuilder sb = new StringBuilder();
        positions.forEach((lexeme, pos) -> {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            quote(sb, lexeme);
            sb.append(':');
            for (int j = 0; j < pos.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(pos.get(j));
            }
        });
        return sb.toString();
    }

    /** OR tsquery literal over the distinct usable tokens, e.g. {@code 'a' | 'b'}; null when none. */
    static String orQuery(List<String> tokens, int maxTerms) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String token : tokens) {
            if (distinct.size() >= maxTerms) {
                break;
            }
            if (usable(token)) {
                distinct.add(token);
            }
        }
        if (distinct.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String lexeme : distinct) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            quote(sb, lexeme);
        }
        return sb.toString();
    }

    /** LIKE pattern matching strings that start with {@code prefix}, for the default escape {@code \}. */
    static String likePrefix(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 4);
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    /** PostgreSQL text cannot hold U+0000. */
    static boolean hasNul(String s) {
        return s != null && s.indexOf('\0') >= 0;
    }

    private static boolean usable(String token) {
        return token != null && !token.isEmpty() && !hasNul(token)
                && token.getBytes(StandardCharsets.UTF_8).length <= MAX_LEXEME_BYTES;
    }

    /** Quoted lexeme: backslash and single quote are escaped by doubling. */
    private static void quote(StringBuilder sb, String lexeme) {
        sb.append('\'');
        for (int i = 0; i < lexeme.length(); i++) {
            char c = lexeme.charAt(i);
            if (c == '\'' || c == '\\') {
                sb.append(c);
            }
            sb.append(c);
        }
        sb.append('\'');
    }
}
