package io.github.qwzhang01.agent.rag.generate;

import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.SupportVerdict;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits generator output into sentences and extracts inline citation markers.
 * <p>
 * A marker is {@code [id]}, {@code [id1, id2]} or {@code 【id】}; adjacent markers accumulate.
 * Bracket text counts as a marker only when every comma-separated token is free of whitespace and
 * either contains {@code #} or is an allowed id, so {@code [x]}, {@code [0]} and Markdown links
 * stay as text. Both {@code 句子[id]。} and {@code 句子。[id]} attach the ids to that sentence;
 * a line holding only markers attaches them to the previous sentence. Fenced code blocks become
 * one sentence each and are never split or scanned for markers.
 */
public final class CitationParser {

    private static final Pattern LEADING_MARKUP = Pattern.compile("^(?:>\\s*)*(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)?");
    private static final Pattern LIST_NUMBER = Pattern.compile("^\\s*(?:[-*+]\\s*)?\\d+$");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([。！？，；：、.!?,;:])");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");
    private static final int MAX_MARKER_LENGTH = 400;

    private CitationParser() {
    }

    /**
     * @param sentences      non-empty sentences in output order, verdict {@link SupportVerdict#UNVERIFIED}
     * @param unknownCitations cited ids dropped because they were not in the allowed set
     */
    public record ParseResult(List<AnswerSentence> sentences, int unknownCitations) {
        public ParseResult {
            sentences = List.copyOf(sentences);
        }
    }

    /**
     * @param text       raw model output
     * @param allowedIds chunk ids the model was shown; other cited ids are dropped and counted
     */
    public static ParseResult parse(String text, Set<String> allowedIds) {
        Objects.requireNonNull(allowedIds, "allowedIds");
        if (text == null || text.isBlank()) {
            return new ParseResult(List.of(), 0);
        }
        return new Run(allowedIds).parse(text);
    }

    private static final class Segment {
        final StringBuilder text = new StringBuilder();
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        final boolean code;
        String cleaned;

        Segment(boolean code) {
            this.code = code;
        }

        boolean hasText() {
            return !clean().isEmpty();
        }

        String clean() {
            if (cleaned == null) {
                cleaned = code ? stripBlankEdges(text.toString()) : cleanProse(text.toString());
            }
            return cleaned;
        }
    }

    private static final class Run {
        private final Set<String> allowed;
        private final List<Segment> out = new ArrayList<>();
        private int unknown;

        Run(Set<String> allowed) {
            this.allowed = allowed;
        }

        ParseResult parse(String text) {
            String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            Segment code = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (code != null) {
                    if (trimmed.startsWith("```")) {
                        emit(code);
                        code = null;
                        String rest = trimmed.substring(3).trim();
                        if (!rest.isEmpty()) {
                            processLine(rest);
                        }
                    } else {
                        code.text.append(line).append('\n');
                    }
                } else if (trimmed.startsWith("```")) {
                    code = new Segment(true);
                } else {
                    processLine(line);
                }
            }
            if (code != null) {
                emit(code);
            }
            List<AnswerSentence> sentences = new ArrayList<>();
            for (Segment s : out) {
                sentences.add(new AnswerSentence(s.clean(), List.copyOf(s.ids), SupportVerdict.UNVERIFIED, ""));
            }
            return new ParseResult(sentences, unknown);
        }

        private void processLine(String line) {
            Segment cur = new Segment(false);
            Segment lastClosed = null;
            int i = 0;
            int n = line.length();
            while (i < n) {
                char c = line.charAt(i);
                if (c == '[' || c == '【') {
                    int end = markerEnd(line, i);
                    if (end > 0) {
                        List<String> ids = acceptIds(line.substring(i + 1, end));
                        Segment target = lastClosed != null && !cur.hasText() && cur.ids.isEmpty() ? lastClosed : cur;
                        target.ids.addAll(ids);
                        cur.cleaned = null;
                        i = end + 1;
                        continue;
                    }
                }
                cur.text.append(c);
                cur.cleaned = null;
                i++;
                if (isTerminal(line, i - 1, cur)) {
                    while (i < n && isClosingQuote(line.charAt(i))) {
                        cur.text.append(line.charAt(i));
                        i++;
                    }
                    if (cur.hasText()) {
                        emit(cur);
                        lastClosed = cur;
                    } else {
                        attachToPrevious(cur, lastClosed);
                    }
                    cur = new Segment(false);
                }
            }
            if (cur.hasText()) {
                emit(cur);
            } else {
                attachToPrevious(cur, lastClosed);
            }
        }

        private void attachToPrevious(Segment empty, Segment lastClosed) {
            if (empty.ids.isEmpty()) {
                return;
            }
            Segment target = lastClosed != null ? lastClosed : (out.isEmpty() ? null : out.get(out.size() - 1));
            if (target != null) {
                target.ids.addAll(empty.ids);
            }
        }

        private void emit(Segment s) {
            if (s.hasText()) {
                out.add(s);
            }
        }

        /** Index of the closing bracket when {@code [..]} at {@code start} is a citation marker, else -1. */
        private int markerEnd(String line, int start) {
            char close = line.charAt(start) == '[' ? ']' : '】';
            int limit = Math.min(line.length(), start + MAX_MARKER_LENGTH);
            for (int j = start + 1; j < limit; j++) {
                char c = line.charAt(j);
                if (c == close) {
                    if (j + 1 < line.length() && line.charAt(j + 1) == '(') {
                        return -1;
                    }
                    return isMarkerBody(line.substring(start + 1, j)) ? j : -1;
                }
                if (c == '[' || c == ']' || c == '【' || c == '】') {
                    return -1;
                }
            }
            return -1;
        }

        private boolean isMarkerBody(String body) {
            List<String> tokens = tokens(body);
            if (tokens.isEmpty()) {
                return false;
            }
            for (String t : tokens) {
                if (t.isEmpty() || t.chars().anyMatch(Character::isWhitespace)) {
                    return false;
                }
                if (t.indexOf('#') < 0 && !allowed.contains(t)) {
                    return false;
                }
            }
            return true;
        }

        private List<String> acceptIds(String body) {
            List<String> kept = new ArrayList<>();
            for (String t : tokens(body)) {
                if (allowed.contains(t)) {
                    kept.add(t);
                } else {
                    unknown++;
                }
            }
            return kept;
        }
    }

    private static List<String> tokens(String body) {
        List<String> tokens = new ArrayList<>();
        for (String raw : body.split("[,，;；]")) {
            tokens.add(raw.trim());
        }
        return tokens;
    }

    private static boolean isTerminal(String line, int idx, Segment cur) {
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
                || line.charAt(next) == '['
                || line.charAt(next) == '【';
        if (!boundary) {
            return false;
        }
        if (c == '.') {
            String before = cur.text.substring(0, cur.text.length() - 1);
            return !LIST_NUMBER.matcher(before).matches();
        }
        return true;
    }

    private static boolean isClosingQuote(char c) {
        return c == '"' || c == '\'' || c == '”' || c == '’' || c == ')' || c == '）' || c == '」' || c == '』';
    }

    private static String cleanProse(String raw) {
        String s = raw.strip();
        s = LEADING_MARKUP.matcher(s).replaceFirst("");
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = SPACE_BEFORE_PUNCT.matcher(s).replaceAll("$1");
        s = s.strip();
        return hasLetterOrDigit(s) ? s : "";
    }

    private static String stripBlankEdges(String raw) {
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
