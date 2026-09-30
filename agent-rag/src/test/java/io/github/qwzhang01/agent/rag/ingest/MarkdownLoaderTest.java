package io.github.qwzhang01.agent.rag.ingest;

import io.github.qwzhang01.agent.rag.model.BlockType;
import io.github.qwzhang01.agent.rag.model.DocumentBlock;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLoaderTest {

    static Path fixture() throws Exception {
        return Path.of(MarkdownLoaderTest.class.getResource("/ingest/rag-guide.md").toURI());
    }

    private final MarkdownLoader loader = new MarkdownLoader();

    @Test
    void supportsMarkdownExtensionsOnly() {
        assertTrue(loader.supports(Path.of("a/README.md")));
        assertTrue(loader.supports(Path.of("notes.MARKDOWN")));
        assertFalse(loader.supports(Path.of("a.pdf")));
        assertFalse(loader.supports(Path.of("md")));
    }

    @Test
    void parsesFixtureIntoOrderedBlocks() throws Exception {
        Path file = fixture();
        ParsedDocument doc = loader.load(file, "docs/rag-guide.md");

        assertEquals("docs/rag-guide.md", doc.docId());
        assertEquals("Agent4j RAG 指南", doc.title());
        assertEquals(file.toAbsolutePath().toString(), doc.source());
        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        assertEquals(expectedHash, doc.contentHash());
        assertEquals(Map.of("title", "检索增强指南", "version", "2.1", "product", "agent4j"), doc.metadata());

        List<DocumentBlock> b = doc.blocks();
        assertEquals(List.of(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.HEADING, BlockType.PARAGRAPH,
                BlockType.CODE, BlockType.HEADING, BlockType.TABLE, BlockType.LIST, BlockType.QUOTE,
                BlockType.HEADING, BlockType.PARAGRAPH, BlockType.CODE), b.stream().map(DocumentBlock::type).toList());

        assertBlock(b.get(0), 1, List.of("Agent4j RAG 指南"), 7, 7);
        assertBlock(b.get(1), 0, List.of("Agent4j RAG 指南"), 9, 9);
        assertTrue(b.get(1).text().contains("It covers **ingestion** and retrieval."));
        assertBlock(b.get(2), 2, List.of("Agent4j RAG 指南", "安装 Installation"), 11, 11);
        assertEquals("安装 Installation", b.get(2).text());
        assertBlock(b.get(4), 0, List.of("Agent4j RAG 指南", "安装 Installation"), 15, 19);
        assertEquals("<dependency>\n  <artifactId>agent-rag</artifactId>\n</dependency>", b.get(4).text());
        assertBlock(b.get(5), 3, List.of("Agent4j RAG 指南", "安装 Installation", "配置项"), 21, 21);
        assertBlock(b.get(6), 0, List.of("Agent4j RAG 指南", "安装 Installation", "配置项"), 23, 26);
        assertEquals("""
                | 参数 | 默认值 | 说明 |
                | --- | --- | --- |
                | targetChars | 800 | 目标块长度 |
                | maxChars | 1500 | 最大块长度 |""", b.get(6).text());
        assertBlock(b.get(7), 0, List.of("Agent4j RAG 指南", "安装 Installation", "配置项"), 28, 30);
        assertTrue(b.get(7).text().contains("基于 PDFBox"));
        assertBlock(b.get(8), 0, List.of("Agent4j RAG 指南", "安装 Installation", "配置项"), 32, 33);
        assertEquals("注意：表格和代码块不会被切开。\nTables and code are atomic.", b.get(8).text());
        assertBlock(b.get(9), 2, List.of("Agent4j RAG 指南", "检索 Retrieval"), 35, 35);
        assertBlock(b.get(11), 0, List.of("Agent4j RAG 指南", "检索 Retrieval"), 39, 40);
        assertEquals("indented code line\n  second line", b.get(11).text());
        b.forEach(block -> assertNull(block.page()));
    }

    @Test
    void titleFallsBackToFrontMatterThenFileName(@TempDir Path dir) throws Exception {
        Path withFm = dir.resolve("fm.md");
        Files.writeString(withFm, "---\ntitle: 'Quoted'\n---\n## Only H2\n\ntext\n", StandardCharsets.UTF_8);
        assertEquals("Quoted", loader.load(withFm, "fm.md").title());

        Path plain = dir.resolve("说明文档.markdown");
        Files.writeString(plain, "## 小节\n\n内容。\n", StandardCharsets.UTF_8);
        ParsedDocument doc = loader.load(plain, "说明文档.markdown");
        assertEquals("说明文档", doc.title());
        assertTrue(doc.metadata().isEmpty());
    }

    @Test
    void unterminatedFrontMatterIsTreatedAsContent() {
        Map<String, String> meta = new LinkedHashMap<>();
        String text = "---\nkey: v\nno close";
        assertEquals(text, MarkdownLoader.blankOutFrontMatter(text, meta));
        assertTrue(meta.isEmpty());
    }

    @Test
    void frontMatterSkipsNestedAndEmptyValuesAndKeepsLineNumbers() {
        Map<String, String> meta = new LinkedHashMap<>();
        String body = MarkdownLoader.blankOutFrontMatter(
                "---\nname: x\ntags:\n  - a\n# comment: y\nempty:\n---\n# H\n", meta);
        assertEquals(Map.of("name", "x"), meta);
        assertEquals("\n".repeat(7) + "# H\n", body);
    }

    @Test
    void handlesCrlfBomAndDeepHeadingPop(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("crlf.md");
        String content = "\uFEFF# A\r\n\r\n### A.1.1\r\n\r\npara one\r\n\r\n## B\r\n\r\npara two\r\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        List<DocumentBlock> blocks = loader.load(file, "crlf.md").blocks();
        assertEquals(List.of("A", "A.1.1"), blocks.get(2).sectionPath());
        assertEquals("para one", blocks.get(2).text());
        assertEquals(5, blocks.get(2).startLine());
        assertEquals(List.of("A", "B"), blocks.get(4).sectionPath());
        assertEquals(9, blocks.get(4).startLine());
        assertEquals("A", loader.load(file, "crlf.md").title());
    }

    @Test
    void skipsThematicBreaksAndKeepsHtmlAsParagraph() {
        List<DocumentBlock> blocks = loader.parse("intro\n\n---\n\n<div>hi</div>\n\n1. one\n2. two\n");
        assertEquals(List.of(BlockType.PARAGRAPH, BlockType.PARAGRAPH, BlockType.LIST),
                blocks.stream().map(DocumentBlock::type).toList());
        assertEquals("<div>hi</div>", blocks.get(1).text());
        assertEquals(List.of(), blocks.get(0).sectionPath());
    }

    private static void assertBlock(DocumentBlock block, int level, List<String> path, int start, int end) {
        assertEquals(level, block.headingLevel(), block::toString);
        assertEquals(path, block.sectionPath(), block::toString);
        assertEquals(start, block.startLine(), block::toString);
        assertEquals(end, block.endLine(), block::toString);
    }
}
