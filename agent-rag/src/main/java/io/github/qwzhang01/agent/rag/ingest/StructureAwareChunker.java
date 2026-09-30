package io.github.qwzhang01.agent.rag.ingest;

import io.github.qwzhang01.agent.rag.Chunker;
import io.github.qwzhang01.agent.rag.model.BlockType;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.DocumentBlock;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Packs consecutive blocks of one section into chunks.
 * <ul>
 *   <li>A chunk never crosses a heading, a section path change or a page change; headings are not emitted
 *       themselves, they become the chunk's {@code sectionPath}.</li>
 *   <li>Blocks are packed until adding the next would exceed {@link ChunkerOptions#targetChars()}.</li>
 *   <li>TABLE and CODE blocks stay whole up to {@link ChunkerOptions#maxChars()}. Beyond that a table
 *       is split by rows, each piece repeating the header row, and code is split by lines.</li>
 *   <li>A PARAGRAPH / LIST / QUOTE block longer than {@code maxChars} is split at sentence ends
 *       ({@code 。！？；}, {@code . ! ?} before whitespace, newlines) into pieces of about
 *       {@code targetChars}, hard-cut when one sentence is too long; pieces may exceed
 *       {@code targetChars} by {@link ChunkerOptions#overlapChars()}.</li>
 *   <li>Pieces of a split block keep the whole block's line range.</li>
 * </ul>
 * Chunk ids are {@code docId + "#" + ordinal}, 0-based in reading order.
 */
public final class StructureAwareChunker implements Chunker {

    /** Chunk metadata key: comma-joined distinct {@link BlockType} names in order of appearance. */
    public static final String BLOCK_TYPES = "blockTypes";

    private static final Logger log = LoggerFactory.getLogger(StructureAwareChunker.class);
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^[|\\s:]*-[|\\s:-]*$");
    private static final String SENTENCE_END_CJK = "。！？；";
    private static final String SENTENCE_END_LATIN = ".!?";
    private static final String CLOSING_MARKS = "”’」』）)\"'";

    private final ChunkerOptions options;

    public StructureAwareChunker() {
        this(ChunkerOptions.defaults());
    }

    public StructureAwareChunker(ChunkerOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public ChunkerOptions options() {
        return options;
    }

    @Override
    public List<Chunk> chunk(ParsedDocument document) {
        Objects.requireNonNull(document, "document");
        Accumulator acc = new Accumulator(document);
        List<String> path = null;
        for (DocumentBlock block : document.blocks()) {
            if (block.type() == BlockType.HEADING) {
                acc.flush();
                path = block.sectionPath();
                continue;
            }
            if (!block.sectionPath().equals(path)) {
                acc.flush();
                path = block.sectionPath();
            }
            if (block.text().isBlank()) {
                continue;
            }
            if (acc.crossesPage(block)) {
                acc.flush();
            }
            for (String piece : pieces(block)) {
                if (!acc.isEmpty() && acc.length() + 2 + piece.length() > options.targetChars()) {
                    acc.flush();
                }
                acc.add(block, piece);
            }
        }
        acc.flush();
        log.debug("Chunked {} into {} chunks", document.docId(), acc.chunks.size());
        return List.copyOf(acc.chunks);
    }

    List<String> pieces(DocumentBlock block) {
        String text = block.text().strip();
        if (text.length() <= options.maxChars()) {
            return List.of(text);
        }
        return switch (block.type()) {
            case TABLE -> splitTable(text);
            case CODE -> splitCode(text);
            default -> splitProse(text);
        };
    }

    private List<String> splitTable(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n")));
        int headerSize = lines.size() > 1 && TABLE_SEPARATOR.matcher(lines.get(1)).matches() ? 2 : 1;
        String header = String.join("\n", lines.subList(0, headerSize));
        return packLines(header, lines.subList(headerSize, lines.size()), options.maxChars());
    }

    private List<String> splitCode(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.length() <= options.maxChars()) {
                lines.add(line);
            } else {
                for (int[] r : hardCut(0, line.length(), line, options.maxChars())) {
                    lines.add(line.substring(r[0], r[1]));
                }
            }
        }
        return packLines("", lines, options.maxChars());
    }

    /** Lines joined by newline under {@code prefix}; every piece holds at least one line. */
    private static List<String> packLines(String prefix, List<String> lines, int limit) {
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder(prefix);
        int count = 0;
        for (String line : lines) {
            if (count > 0 && cur.length() + 1 + line.length() > limit) {
                pieces.add(cur.toString());
                cur = new StringBuilder(prefix);
                count = 0;
            }
            if (!cur.isEmpty() || count > 0) {
                cur.append('\n');
            }
            cur.append(line);
            count++;
        }
        if (count > 0) {
            pieces.add(cur.toString());
        }
        return pieces;
    }

    private List<String> splitProse(String text) {
        int target = options.targetChars();
        List<int[]> ranges = new ArrayList<>();
        int curStart = -1;
        int curEnd = -1;
        for (int[] seg : sentences(text)) {
            if (seg[1] - seg[0] > target) {
                if (curStart >= 0) {
                    ranges.add(new int[]{curStart, curEnd});
                    curStart = -1;
                }
                ranges.addAll(hardCut(seg[0], seg[1], text, target));
            } else if (curStart < 0) {
                curStart = seg[0];
                curEnd = seg[1];
            } else if (seg[1] - curStart > target) {
                ranges.add(new int[]{curStart, curEnd});
                curStart = seg[0];
                curEnd = seg[1];
            } else {
                curEnd = seg[1];
            }
        }
        if (curStart >= 0) {
            ranges.add(new int[]{curStart, curEnd});
        }
        List<String> pieces = new ArrayList<>(ranges.size());
        for (int i = 0; i < ranges.size(); i++) {
            int start = ranges.get(i)[0];
            if (i > 0 && options.overlapChars() > 0) {
                start = safeCut(text, Math.max(ranges.get(i - 1)[0], start - options.overlapChars()));
            }
            String piece = text.substring(start, ranges.get(i)[1]).strip();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
        }
        return pieces;
    }

    /** Consecutive [start, end) ranges covering {@code text}, each ending after a sentence terminator. */
    static List<int[]> sentences(String text) {
        List<int[]> segments = new ArrayList<>();
        int start = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean end = c == '\n'
                    || SENTENCE_END_CJK.indexOf(c) >= 0
                    || (SENTENCE_END_LATIN.indexOf(c) >= 0 && (i + 1 == n || Character.isWhitespace(text.charAt(i + 1))));
            if (!end) {
                continue;
            }
            int j = i + 1;
            while (j < n && CLOSING_MARKS.indexOf(text.charAt(j)) >= 0) {
                j++;
            }
            while (j < n && Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            segments.add(new int[]{start, j});
            start = j;
            i = j - 1;
        }
        if (start < n) {
            segments.add(new int[]{start, n});
        }
        return segments;
    }

    private static List<int[]> hardCut(int from, int to, String text, int size) {
        List<int[]> ranges = new ArrayList<>();
        int start = from;
        while (start < to) {
            int end = Math.min(to, start + size);
            if (end < to) {
                int adjusted = safeCut(text, end);
                end = adjusted > start ? adjusted : end;
            }
            ranges.add(new int[]{start, end});
            start = end;
        }
        return ranges;
    }

    /** Moves a cut point off the low half of a surrogate pair. */
    private static int safeCut(String text, int index) {
        return index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index))
                ? index - 1 : index;
    }

    private static final class Accumulator {

        private final ParsedDocument document;
        private final List<Chunk> chunks = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();
        private final Set<String> types = new LinkedHashSet<>();
        private List<String> sectionPath = List.of();
        private int length;
        private int startLine;
        private int endLine;
        private Integer page;

        Accumulator(ParsedDocument document) {
            this.document = document;
        }

        boolean isEmpty() {
            return texts.isEmpty();
        }

        int length() {
            return length;
        }

        boolean crossesPage(DocumentBlock block) {
            return !texts.isEmpty() && block.page() != null && page != null && !block.page().equals(page);
        }

        void add(DocumentBlock block, String text) {
            if (texts.isEmpty()) {
                sectionPath = block.sectionPath();
            } else {
                length += 2;
            }
            texts.add(text);
            length += text.length();
            types.add(block.type().name());
            if (block.startLine() > 0 && (startLine == 0 || block.startLine() < startLine)) {
                startLine = block.startLine();
            }
            endLine = Math.max(endLine, block.endLine());
            if (page == null) {
                page = block.page();
            }
        }

        void flush() {
            String text = String.join("\n\n", texts);
            if (!text.isBlank()) {
                Map<String, String> metadata = new LinkedHashMap<>(document.metadata());
                metadata.put(BLOCK_TYPES, String.join(",", types));
                chunks.add(new Chunk(document.docId() + "#" + chunks.size(), document.docId(),
                        document.title(), document.source(), sectionPath, text,
                        startLine, endLine, page, metadata));
            }
            texts.clear();
            types.clear();
            sectionPath = List.of();
            length = 0;
            startLine = 0;
            endLine = 0;
            page = null;
        }
    }
}
