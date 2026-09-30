package io.github.qwzhang01.agent.rag.ingest;

import io.github.qwzhang01.agent.rag.model.BlockType;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.DocumentBlock;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAwareChunkerTest {

    private static final List<String> SEC = List.of("S");

    @Test
    void chunksFixtureBySection() throws Exception {
        ParsedDocument doc = new MarkdownLoader().load(MarkdownLoaderTest.fixture(), "docs/rag-guide.md");
        List<Chunk> chunks = new StructureAwareChunker().chunk(doc);

        assertEquals(4, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            assertEquals("docs/rag-guide.md#" + i, c.chunkId());
            assertEquals("Agent4j RAG 指南", c.docTitle());
            assertEquals(doc.source(), c.source());
            assertEquals("2.1", c.metadata().get("version"));
        }

        Chunk install = chunks.get(1);
        assertEquals(List.of("Agent4j RAG 指南", "安装 Installation"), install.sectionPath());
        assertEquals("PARAGRAPH,CODE", install.metadata().get(StructureAwareChunker.BLOCK_TYPES));
        assertEquals(13, install.startLine());
        assertEquals(19, install.endLine());
        assertTrue(install.text().endsWith("<dependency>\n  <artifactId>agent-rag</artifactId>\n</dependency>"));

        Chunk config = chunks.get(2);
        assertEquals("Agent4j RAG 指南 > 安装 Installation > 配置项", config.sectionLabel());
        assertEquals("TABLE,LIST,QUOTE", config.metadata().get(StructureAwareChunker.BLOCK_TYPES));
        assertEquals(23, config.startLine());
        assertEquals(33, config.endLine());
        assertTrue(config.text().startsWith("| 参数 | 默认值 | 说明 |\n| --- | --- | --- |\n"));
        assertTrue(config.text().contains("| maxChars | 1500 | 最大块长度 |\n\n- 支持 Markdown"));

        assertEquals(List.of("Agent4j RAG 指南", "检索 Retrieval"), chunks.get(3).sectionPath());
        chunks.forEach(c -> assertEquals(null, c.page()));
    }

    @Test
    void isDeterministic() throws Exception {
        ParsedDocument doc = new MarkdownLoader().load(MarkdownLoaderTest.fixture(), "d");
        StructureAwareChunker chunker = new StructureAwareChunker(new ChunkerOptions(40, 80, 0));
        List<Chunk> first = chunker.chunk(doc);
        List<Chunk> second = new StructureAwareChunker(new ChunkerOptions(40, 80, 0)).chunk(doc);
        assertEquals(first, second);
        assertTrue(first.size() > 4);
    }

    @Test
    void packsBlocksUpToTargetWithinSection() {
        ParsedDocument doc = doc(
                block(BlockType.PARAGRAPH, "a".repeat(30), SEC, 1, 1),
                block(BlockType.PARAGRAPH, "b".repeat(30), SEC, 3, 3),
                block(BlockType.PARAGRAPH, "c".repeat(30), SEC, 5, 5));
        List<Chunk> chunks = new StructureAwareChunker(new ChunkerOptions(62, 100, 0)).chunk(doc);
        assertEquals(2, chunks.size());
        assertEquals("a".repeat(30) + "\n\n" + "b".repeat(30), chunks.get(0).text());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(3, chunks.get(0).endLine());
        assertEquals("c".repeat(30), chunks.get(1).text());
        assertEquals("PARAGRAPH", chunks.get(1).metadata().get(StructureAwareChunker.BLOCK_TYPES));
    }

    @Test
    void neverCrossesHeadingOrSectionChange() {
        ParsedDocument doc = doc(
                block(BlockType.PARAGRAPH, "preamble", List.of(), 1, 1),
                new DocumentBlock(BlockType.HEADING, "S", 1, SEC, 2, 2, null),
                block(BlockType.PARAGRAPH, "one", SEC, 3, 3),
                new DocumentBlock(BlockType.HEADING, "S", 1, SEC, 4, 4, null),
                block(BlockType.PARAGRAPH, "two", SEC, 5, 5),
                block(BlockType.PARAGRAPH, "three", List.of("T"), 6, 6),
                block(BlockType.PARAGRAPH, "   ", List.of("T"), 7, 7));
        List<Chunk> chunks = new StructureAwareChunker().chunk(doc);
        assertEquals(List.of("preamble", "one", "two", "three"), chunks.stream().map(Chunk::text).toList());
        assertEquals(List.of(List.of(), SEC, SEC, List.of("T")),
                chunks.stream().map(Chunk::sectionPath).toList());
    }

    @Test
    void headingOnlyDocumentYieldsNoChunks() {
        ParsedDocument doc = doc(new DocumentBlock(BlockType.HEADING, "H", 1, List.of("H"), 1, 1, null));
        assertTrue(new StructureAwareChunker().chunk(doc).isEmpty());
    }

    @Test
    void tableAndCodeUpToMaxStayWholeEvenAboveTarget() {
        String table = "| h |\n| --- |\n" + "| row |\n".repeat(10).strip();
        String code = "line\n".repeat(20).strip();
        ParsedDocument doc = doc(
                block(BlockType.TABLE, table, SEC, 1, 12),
                block(BlockType.CODE, code, SEC, 13, 34));
        List<Chunk> chunks = new StructureAwareChunker(new ChunkerOptions(20, 200, 0)).chunk(doc);
        assertEquals(List.of(table, code), chunks.stream().map(Chunk::text).toList());
    }

    @Test
    void oversizeTableSplitsByRowsRepeatingHeader() {
        StringBuilder sb = new StringBuilder("| name | value |\n| --- | --- |");
        for (int i = 0; i < 10; i++) {
            sb.append("\n| key").append(i).append(" | ").append(i).append(" |");
        }
        ParsedDocument doc = doc(block(BlockType.TABLE, sb.toString(), SEC, 10, 21));
        List<Chunk> chunks = new StructureAwareChunker(new ChunkerOptions(60, 80, 0)).chunk(doc);
        assertTrue(chunks.size() > 1);
        int rows = 0;
        for (Chunk c : chunks) {
            assertTrue(c.text().startsWith("| name | value |\n| --- | --- |\n| key"), c.text());
            assertTrue(c.text().length() <= 80);
            assertEquals(10, c.startLine());
            assertEquals(21, c.endLine());
            rows += (int) c.text().lines().count() - 2;
        }
        assertEquals(10, rows);
    }

    @Test
    void oversizeTableWithoutBodyRowsIsKept() {
        String header = "| " + "wide column | ".repeat(10) + "\n|" + " --- |".repeat(10);
        ParsedDocument doc = doc(block(BlockType.TABLE, header, SEC, 1, 2));

        List<Chunk> chunks = new StructureAwareChunker(new ChunkerOptions(20, 40, 0)).chunk(doc);

        assertEquals(List.of(header), chunks.stream().map(Chunk::text).toList());
    }

    @Test
    void oversizeCodeSplitsByLinesAndHardCutsLongLines() {
        String code = "x".repeat(25) + "\n" + "short\n".repeat(5) + "y".repeat(50);
        ParsedDocument doc = doc(block(BlockType.CODE, code, SEC, 1, 7));
        List<Chunk> chunks = new StructureAwareChunker(new ChunkerOptions(20, 30, 0)).chunk(doc);
        String rejoined = String.join("\n", chunks.stream().map(Chunk::text).toList());
        assertEquals(code.replace("y".repeat(50), "y".repeat(30) + "\n" + "y".repeat(20)), rejoined);
        chunks.forEach(c -> assertTrue(c.text().length() <= 30, c.text()));
    }

    @Test
    void oversizeParagraphSplitsAtSentenceBoundaries() {
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            sentences.add(i % 2 == 0 ? "这是第" + i + "个中文句子，用来测试切分。" : "Sentence number " + i + " is English!");
        }
        String text = String.join(" ", sentences);
        ParsedDocument doc = doc(block(BlockType.PARAGRAPH, text, SEC, 3, 3));
        List<Chunk> chunks = new StructureAwareChunker(new ChunkerOptions(60, 100, 0)).chunk(doc);
        assertTrue(chunks.size() > 1);
        for (Chunk c : chunks) {
            assertTrue(c.text().length() <= 60, c.text());
            assertTrue(c.text().endsWith("。") || c.text().endsWith("!"), c.text());
            assertEquals(3, c.startLine());
        }
        assertEquals(text.replace(" ", ""), String.join("", chunks.stream().map(Chunk::text).toList()).replace(" ", ""));
    }

    @Test
    void sentenceLongerThanTargetIsHardCutWithoutBreakingSurrogates() {
        String emoji = "\uD83D\uDE00";
        String text = "短句。" + emoji.repeat(30);
        ParsedDocument doc = doc(block(BlockType.QUOTE, text, SEC, 1, 1));
        List<Chunk> chunks = new StructureAwareChunker(new ChunkerOptions(15, 20, 0)).chunk(doc);
        assertEquals("短句。", chunks.get(0).text());
        for (Chunk c : chunks) {
            assertTrue(c.text().length() <= 15);
            assertTrue(!Character.isLowSurrogate(c.text().charAt(0)));
        }
        assertEquals(text, String.join("", chunks.stream().map(Chunk::text).toList()));
    }

    @Test
    void overlapPrependsTailOfPreviousPiece() {
        String text = "Alpha one. Bravo two. Charlie three. Delta four.";
        ParsedDocument doc = doc(block(BlockType.LIST, text, SEC, 1, 1));
        List<Chunk> chunks = new StructureAwareChunker(new ChunkerOptions(22, 30, 5)).chunk(doc);
        assertEquals("Alpha one. Bravo two.", chunks.get(0).text());
        assertEquals("two. Charlie three.", chunks.get(1).text());
    }

    @Test
    void noChunkSpansTwoPages() {
        ParsedDocument doc = doc(
                block(BlockType.PARAGRAPH, "p2a", List.of(), 0, 0, 2),
                block(BlockType.PARAGRAPH, "p2b", List.of(), 0, 0, 2),
                block(BlockType.PARAGRAPH, "p3", List.of(), 0, 0, 3),
                block(BlockType.PARAGRAPH, "no page", List.of(), 0, 0, null),
                block(BlockType.PARAGRAPH, "p4", List.of(), 0, 0, 4));
        List<Chunk> chunks = new StructureAwareChunker().chunk(doc);
        assertEquals(List.of("p2a\n\np2b", "p3\n\nno page", "p4"), chunks.stream().map(Chunk::text).toList());
        assertEquals(List.of(2, 3, 4), chunks.stream().map(Chunk::page).toList());
        assertEquals(List.of("doc#0", "doc#1", "doc#2"), chunks.stream().map(Chunk::chunkId).toList());
        assertEquals(0, chunks.get(0).startLine());
        assertEquals(0, chunks.get(0).endLine());
    }

    @Test
    void sentencesCoverWholeTextIncludingClosingQuotes() {
        String text = "他说：“好。”然后离开。 End? yes";
        List<int[]> segs = StructureAwareChunker.sentences(text);
        assertEquals(List.of("他说：“好。”", "然后离开。 ", "End? ", "yes"),
                segs.stream().map(r -> text.substring(r[0], r[1])).toList());
    }

    @Test
    void optionsValidate() {
        assertEquals(new ChunkerOptions(800, 1500, 0), ChunkerOptions.defaults());
        assertThrows(IllegalArgumentException.class, () -> new ChunkerOptions(0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkerOptions(10, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkerOptions(10, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new ChunkerOptions(10, 20, -1));
        assertThrows(NullPointerException.class, () -> new StructureAwareChunker(null));
    }

    private static DocumentBlock block(BlockType type, String text, List<String> path, int start, int end) {
        return block(type, text, path, start, end, null);
    }

    private static DocumentBlock block(BlockType type, String text, List<String> path, int start, int end, Integer page) {
        return new DocumentBlock(type, text, 0, path, start, end, page);
    }

    private static ParsedDocument doc(DocumentBlock... blocks) {
        return new ParsedDocument("doc", "Doc", "/tmp/doc", "hash", List.of(blocks), Map.of("k", "v"));
    }
}
