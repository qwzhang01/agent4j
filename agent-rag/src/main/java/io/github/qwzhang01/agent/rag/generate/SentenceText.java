package io.github.qwzhang01.agent.rag.generate;

import java.util.regex.Pattern;

/** Sentence boundary detection and cleanup for {@link CitationParser}. */
final class SentenceText {

    private static final Pattern LEADING_MARKUP = Pattern.compile("^(?:>\\s*)*(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)?");
    private static final Pattern LIST_NUMBER = Pattern.compile("^\\s*(?:[-*+]\\s*)?\\d+$");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([。！？，；：、.!?,;:])");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    private SentenceText() {
    }

    /**
     * Whether the char at {@code idx} ends a sentence. {@code textSoFar} is the current sentence
     * including that char; an ASCII terminal must be followed by whitespace, a marker or line end,
     * and a period after a bare list number ("1.") does not count.
     */
    static boolean isTerminal(String line, int idx, CharSequence textSoFar) {
        char c = line.charAt(idx);
        if (c == '。' || c == '！' || c == '？') {
            return true;
        }
        if (c != '.' && c != '!' && c != '?') {
            return false;
        }
        int next = idx + 1;
        while (next < line.length() && isClosingQuote(line.charAt(next))) {
            next++;
        }
        boolean boundary = next >= line.length()
                || Character.isWhitespace(line.charAt(next))
                || CitationParser.closingBracket(line.charAt(next)) != 0;
        if (!boundary) {
            return false;
        }
        if (c == '.') {
            String before = textSoFar.subSequence(0, textSoFar.length() - 1).toString();
            return !LIST_NUMBER.matcher(before).matches();
        }
        return true;
    }

    static boolean isClosingQuote(char c) {
        return c == '"' || c == '\'' || c == '”' || c == '’' || c == ')' || c == '）' || c == '」' || c == '』';
    }

    static String cleanProse(String raw) {
        String s = raw.strip();
        s = LEADING_MARKUP.matcher(s).replaceFirst("");
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = SPACE_BEFORE_PUNCT.matcher(s).replaceAll("$1");
        s = s.strip();
        return hasLetterOrDigit(s) ? s : "";
    }

    static String stripBlankEdges(String raw) {
        String s = raw.stripTrailing();
        while (s.startsWith("\n")) {
            s = s.substring(1);
        }
        return hasLetterOrDigit(s) ? s : "";
    }

    private static boolean hasLetterOrDigit(String s) {
        return s.codePoints().anyMatch(Character::isLetterOrDigit);
    }
}
