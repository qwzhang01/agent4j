package io.github.qwzhang01.agent.rag.ingest;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.qwzhang01.agent.rag.DocumentLoader;
import io.github.qwzhang01.agent.rag.model.BlockType;
import io.github.qwzhang01.agent.rag.model.DocumentBlock;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads {@code .md} / {@code .markdown} files (CommonMark + GFM tables) into structural blocks.
 * <p>
 * A leading YAML front matter block is read as flat {@code key: value} metadata and excluded from
 * the blocks. The title is the first H1, else the front matter {@code title}, else the file name.
 */
public final class MarkdownLoader implements DocumentLoader {

    private static final Pattern QUOTE_MARKER = Pattern.compile("(?m)^ {0,3}> ?");

    private final Parser parser;

    public MarkdownLoader() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    @Override
    public boolean supports(Path file) {
        return FileSupport.hasExtension(file, ".md", ".markdown");
    }

    @Override
    public ParsedDocument load(Path file, String docId) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(docId, "docId");
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n");
        Map<String, String> metadata = new LinkedHashMap<>();
        text = blankOutFrontMatter(text, metadata);

        List<DocumentBlock> blocks = parse(text);
        String title = blocks.stream()
                .filter(b -> b.type() == BlockType.HEADING && b.headingLevel() == 1 && !b.text().isBlank())
                .map(DocumentBlock::text)
                .findFirst()
                .orElseGet(() -> metadata.getOrDefault("title", FileSupport.baseName(file)));
        return new ParsedDocument(docId, title, file.toAbsolutePath().toString(),
                FileSupport.sha256Hex(bytes), blocks, metadata);
    }

    /** Parses Markdown text into blocks; line numbers are 1-based positions in {@code text}. */
    List<DocumentBlock> parse(String text) {
        Document document = parser.parse(text);
        int[] lineStarts = lineStarts(text);
        Deque<Section> sections = new ArrayDeque<>();
        List<DocumentBlock> blocks = new ArrayList<>();
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            BlockType type = typeOf(node);
            if (type == null) {
                continue;
            }
            int startLine = lineOf(lineStarts, node.getStartOffset());
            int endLine = lineOf(lineStarts, lastContentOffset(text, node));
            if (type == BlockType.HEADING) {
                Heading heading = (Heading) node;
                String title = new TextCollectingVisitor().collectAndGetText(heading).trim();
                while (!sections.isEmpty() && sections.peek().level() >= heading.getLevel()) {
                    sections.pop();
                }
                sections.push(new Section(heading.getLevel(), title));
                blocks.add(new DocumentBlock(type, title, heading.getLevel(), pathOf(sections),
                        startLine, endLine, null));
                continue;
            }
            String body = textOf(node, type);
            if (!body.isBlank()) {
                blocks.add(new DocumentBlock(type, body, 0, pathOf(sections), startLine, endLine, null));
            }
        }
        return blocks;
    }

    private static BlockType typeOf(Node node) {
        if (node instanceof Heading) {
            return BlockType.HEADING;
        }
        if (node instanceof Paragraph || node instanceof HtmlBlock) {
            return BlockType.PARAGRAPH;
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            return BlockType.LIST;
        }
        if (node instanceof TableBlock) {
            return BlockType.TABLE;
        }
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
            return BlockType.CODE;
        }
        if (node instanceof BlockQuote) {
            return BlockType.QUOTE;
        }
        return null;
    }

    private static String textOf(Node node, BlockType type) {
        return switch (type) {
            case TABLE -> renderTable((TableBlock) node);
            case CODE -> node instanceof FencedCodeBlock fenced
                    ? stripTrailingNewlines(fenced.getContentChars().toString())
                    : unindent(node.getChars().toString());
            case QUOTE -> QUOTE_MARKER.matcher(node.getChars().toString()).replaceAll("").strip();
            default -> node.getChars().toString().strip();
        };
    }

    private static String renderTable(TableBlock table) {
        List<String> lines = new ArrayList<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            if (!(section instanceof TableHead) && !(section instanceof TableBody)) {
                continue;
            }
            int columns = 0;
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (row instanceof TableRow) {
                    List<String> cells = new ArrayList<>();
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof TableCell tableCell) {
                            cells.add(tableCell.getText().toString().strip());
                        }
                    }
                    columns = Math.max(columns, cells.size());
                    lines.add("| " + String.join(" | ", cells) + " |");
                }
            }
            if (section instanceof TableHead && columns > 0) {
                lines.add("|" + " --- |".repeat(columns));
            }
        }
        return String.join("\n", lines);
    }

    private static String unindent(String code) {
        StringBuilder sb = new StringBuilder();
        for (String line : stripTrailingNewlines(code).split("\n", -1)) {
            int i = 0;
            if (line.startsWith("\t")) {
                i = 1;
            } else {
                while (i < 4 && i < line.length() && line.charAt(i) == ' ') {
                    i++;
                }
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line.substring(i));
        }
        return sb.toString();
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static int lastContentOffset(String text, Node node) {
        int end = Math.min(node.getEndOffset(), text.length());
        while (end > node.getStartOffset() + 1 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return Math.max(node.getStartOffset(), end - 1);
    }

    private static int[] lineStarts(String text) {
        int[] starts = new int[16];
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        return Arrays.copyOf(starts, count);
    }

    private static int lineOf(int[] lineStarts, int offset) {
        int idx = Arrays.binarySearch(lineStarts, offset);
        return idx >= 0 ? idx + 1 : -idx - 1;
    }

    private static List<String> pathOf(Deque<Section> sections) {
        List<String> path = new ArrayList<>(sections.size());
        sections.descendingIterator().forEachRemaining(s -> path.add(s.title()));
        return path;
    }

    /**
     * Reads a leading {@code ---} front matter block into {@code metadata} and replaces its lines with
     * empty lines, so block line numbers still match the original file.
     */
    static String blankOutFrontMatter(String text, Map<String, String> metadata) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].strip().equals("---")) {
            return text;
        }
        int close = -1;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i].strip();
            if (l.equals("---") || l.equals("...")) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            return text;
        }
        for (int i = 1; i < close; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0)) || line.startsWith("#")) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = unquote(line.substring(colon + 1).strip());
            if (!key.isEmpty() && !value.isEmpty()) {
                metadata.put(key, value);
            }
        }
        StringBuilder sb = new StringBuilder(text.length());
        sb.append("\n".repeat(close + 1));
        for (int i = close + 1; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String unquote(String v) {
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    private record Section(int level, String title) {
    }
}
