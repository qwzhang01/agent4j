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
 * A marker is {@code [id]}, {@code [id1, id2]}, {@code 【id】} or {@code ［id］}; adjacent markers
 * accumulate. Bracket text counts as a marker only when every comma-separated token is an allowed id
 * or has the chunk-id shape {@code <docId>#<ordinal>} (and is not a URL), so {@code [x]}, {@code [0]},
 * {@code [C#]} and Markdown links stay as text. Both {@code 句子[id]。} and {@code 句子。[id]} attach
 * the ids to that sentence; a line holding only markers attaches them to the previous sentence.
 * Fenced code blocks become one sentence each and, like inline code spans, are never scanned for markers.
 */
public final class CitationParser {

    private static final Pattern CHUNK_ID_SHAPE = Pattern.compile("\\S+#\\d+");
    private static final int MAX_MARKER_LENGTH = 400;

    private CitationParser() {
    }

    /**
     * @param sentences        non-empty sentences in output order, verdict {@link SupportVerdict#UNVERIFIED}
     * @param unknownCitations cited ids dropped because they were not in the allowed set
     * @param text             the input with dropped ids removed from its markers, for display
     */
    public record ParseResult(List<AnswerSentence> sentences, int unknownCitations, String text) {
        public ParseResult {
            sentences = List.copyOf(sentences);
            text = text == null ? "" : text;
        }
    }

    /**
     * @param text       raw model output
     * @param allowedIds chunk ids the model was shown; other cited ids are dropped and counted
     */
    public static ParseResult parse(String text, Set<String> allowedIds) {
        Objects.requireNonNull(allowedIds, "allowedIds");
        if (text == null || text.isBlank()) {
            return new ParseResult(List.of(), 0, text);
        }
        return new Run(allowedIds).parse(text);
    }

    /** Closing counterpart of a marker's opening bracket, or 0 when {@code open} opens no marker. */
    static char closingBracket(char open) {
        return switch (open) {
            case '[' -> ']';
            case '【' -> '】';
            case '［' -> '］';
            default -> 0;
        };
    }

    private static boolean isBracket(char c) {
        return closingBracket(c) != 0 || c == ']' || c == '】' || c == '］';
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
                cleaned = code ? SentenceText.stripBlankEdges(text.toString()) : SentenceText.cleanProse(text.toString());
            }
            return cleaned;
        }

        void append(CharSequence s) {
            text.append(s);
            cleaned = null;
        }
    }

    private static final class Run {
        private final Set<String> allowed;
        private final List<Segment> out = new ArrayList<>();
        private final StringBuilder display = new StringBuilder();
        private int unknown;

        Run(Set<String> allowed) {
            this.allowed = allowed;
        }

        ParseResult parse(String text) {
            String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            Segment code = null;
            for (int n = 0; n < lines.length; n++) {
                String line = lines[n];
                if (n > 0) {
                    display.append('\n');
                }
                String trimmed = line.trim();
                if (code != null) {
                    if (trimmed.startsWith("```")) {
                        emit(code);
                        code = null;
                        String rest = trimmed.substring(3).trim();
                        display.append(line, 0, line.indexOf("```") + 3);
                        if (!rest.isEmpty()) {
                            display.append(' ');
                            processLine(rest);
                        }
                    } else {
                        code.append(line + "\n");
                        display.append(line);
                    }
                } else if (trimmed.startsWith("```")) {
                    code = new Segment(true);
                    display.append(line);
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
            return new ParseResult(sentences, unknown, display.toString());
        }

        private void processLine(String line) {
            Segment cur = new Segment(false);
            Segment lastClosed = null;
            int i = 0;
            int n = line.length();
            while (i < n) {
                char c = line.charAt(i);
                if (closingBracket(c) != 0) {
                    int end = markerEnd(line, i);
                    if (end > 0) {
                        List<String> ids = acceptIds(line, i, end);
                        Segment target = lastClosed != null && !cur.hasText() && cur.ids.isEmpty() ? lastClosed : cur;
                        target.ids.addAll(ids);
                        i = end + 1;
                        continue;
                    }
                }
                if (c == '`') {
                    int end = inlineCodeEnd(line, i);
                    if (end > 0) {
                        cur.append(line.substring(i, end));
                        display.append(line, i, end);
                        i = end;
                        continue;
                    }
                }
                cur.append(String.valueOf(c));
                display.append(c);
                i++;
                if (SentenceText.isTerminal(line, i - 1, cur.text)) {
                    while (i < n && SentenceText.isClosingQuote(line.charAt(i))) {
                        cur.append(String.valueOf(line.charAt(i)));
                        display.append(line.charAt(i));
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

        /** Index of the closing bracket when the bracket at {@code start} opens a citation marker, else -1. */
        private int markerEnd(String line, int start) {
            char close = closingBracket(line.charAt(start));
            int limit = Math.min(line.length(), start + MAX_MARKER_LENGTH);
            for (int j = start + 1; j < limit; j++) {
                char c = line.charAt(j);
                if (c == close) {
                    if (j + 1 < line.length() && line.charAt(j + 1) == '(') {
                        return -1;
                    }
                    return isMarkerBody(line.substring(start + 1, j)) ? j : -1;
                }
                if (isBracket(c)) {
                    return -1;
                }
            }
            return -1;
        }

        private boolean isMarkerBody(String body) {
            List<String> tokens = tokens(body);
            for (String t : tokens) {
                boolean idShaped = CHUNK_ID_SHAPE.matcher(t).matches() && !t.contains("://");
                if (!allowed.contains(t) && !idShaped) {
                    return false;
                }
            }
            return !tokens.isEmpty();
        }

        /** Allowed ids of the marker at {@code [start, end]}; writes the marker minus unknown ids to the display text. */
        private List<String> acceptIds(String line, int start, int end) {
            List<String> tokens = tokens(line.substring(start + 1, end));
            List<String> kept = new ArrayList<>();
            for (String t : tokens) {
                if (allowed.contains(t)) {
                    kept.add(t);
                } else {
                    unknown++;
                }
            }
            if (kept.size() == tokens.size()) {
                display.append(line, start, end + 1);
            } else if (kept.isEmpty()) {
                while (!display.isEmpty() && (display.charAt(display.length() - 1) == ' '
                        || display.charAt(display.length() - 1) == '\t')) {
                    display.setLength(display.length() - 1);
                }
            } else {
                kept.forEach(id -> display.append('[').append(id).append(']'));
            }
            return kept;
        }
    }

    /** End (exclusive) of the inline code span opened by the backtick run at {@code start}, or -1 if unclosed. */
    private static int inlineCodeEnd(String line, int start) {
        int ticks = 0;
        while (start + ticks < line.length() && line.charAt(start + ticks) == '`') {
            ticks++;
        }
        String fence = "`".repeat(ticks);
        int close = line.indexOf(fence, start + ticks);
        return close < 0 ? -1 : close + ticks;
    }

    private static List<String> tokens(String body) {
        List<String> tokens = new ArrayList<>();
        for (String raw : body.split("[,，;；]")) {
            String t = raw.trim();
            if (t.isEmpty()) {
                return List.of();
            }
            tokens.add(t);
        }
        return tokens;
    }
}
