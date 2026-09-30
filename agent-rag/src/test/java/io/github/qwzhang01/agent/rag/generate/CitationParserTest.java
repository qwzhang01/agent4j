package io.github.qwzhang01.agent.rag.generate;

import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationParserTest {

    private static final Set<String> IDS = Set.of(
            "docs/a.md#1", "docs/a.md#2", "b-c_d.md#10", "笔记/记忆系统.md#3");

    private static List<AnswerSentence> parse(String text) {
        return CitationParser.parse(text, IDS).sentences();
    }

    @Test
    void markerBeforePunctuationAttachesToSentence() {
        List<AnswerSentence> s = parse("召回分两路[docs/a.md#1]。第二句[docs/a.md#2]。");
        assertEquals(2, s.size());
        assertEquals("召回分两路。", s.get(0).text());
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
        assertEquals("第二句。", s.get(1).text());
        assertEquals(List.of("docs/a.md#2"), s.get(1).citedChunkIds());
    }

    @Test
    void markerAfterPunctuationAttachesToPreviousSentence() {
        List<AnswerSentence> s = parse("召回分两路。[docs/a.md#1] 第二句。[docs/a.md#2]");
        assertEquals(2, s.size());
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
        assertEquals("第二句。", s.get(1).text());
        assertEquals(List.of("docs/a.md#2"), s.get(1).citedChunkIds());
    }

    @Test
    void adjacentAndCommaSeparatedMarkers() {
        List<AnswerSentence> s = parse("A 句[docs/a.md#1][b-c_d.md#10]。B 句[docs/a.md#2, 笔记/记忆系统.md#3]。");
        assertEquals(List.of("docs/a.md#1", "b-c_d.md#10"), s.get(0).citedChunkIds());
        assertEquals(List.of("docs/a.md#2", "笔记/记忆系统.md#3"), s.get(1).citedChunkIds());
        assertEquals("B 句。", s.get(1).text());
    }

    @Test
    void duplicateIdsAreDeduplicated() {
        List<AnswerSentence> s = parse("句子[docs/a.md#1][docs/a.md#1]。");
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
    }

    @Test
    void unknownIdsAreDroppedAndCounted() {
        CitationParser.ParseResult r = CitationParser.parse(
                "句子[docs/a.md#1][ghost.md#9]。另一句[x.md#1, docs/a.md#2]。", IDS);
        assertEquals(2, r.unknownCitations());
        assertEquals(List.of("docs/a.md#1"), r.sentences().get(0).citedChunkIds());
        assertEquals(List.of("docs/a.md#2"), r.sentences().get(1).citedChunkIds());
    }

    @Test
    void nonCitationBracketsStayAsText() {
        List<AnswerSentence> s = parse("数组 arr[0] 和 [链接](http://x) 以及 [x] 选项[docs/a.md#1]。");
        assertEquals(1, s.size());
        assertEquals("数组 arr[0] 和 [链接](http://x) 以及 [x] 选项。", s.get(0).text());
        assertEquals(0, CitationParser.parse("arr[0] [x]", IDS).unknownCitations());
    }

    @Test
    void englishSentencesSplitOnPunctuationFollowedBySpace() {
        List<AnswerSentence> s = parse(
                "Set top_k to 5 [docs/a.md#1]. Version 1.2 is required[docs/a.md#2]! Why? Because.");
        assertEquals(4, s.size());
        assertEquals("Set top_k to 5.", s.get(0).text());
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
        assertEquals("Version 1.2 is required!", s.get(1).text());
        assertEquals(List.of("docs/a.md#2"), s.get(1).citedChunkIds());
        assertTrue(s.get(2).citedChunkIds().isEmpty());
    }

    @Test
    void englishPeriodDirectlyFollowedByMarker() {
        List<AnswerSentence> s = parse("First claim.[docs/a.md#1] Second claim.[docs/a.md#2]");
        assertEquals(2, s.size());
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
        assertEquals(List.of("docs/a.md#2"), s.get(1).citedChunkIds());
    }

    @Test
    void newlineListItemsAreSentences() {
        String text = """
                实现要点：
                - 第一点 [docs/a.md#1]
                - 第二点[docs/a.md#2]
                1. 编号项[b-c_d.md#10]
                -
                """;
        List<AnswerSentence> s = parse(text);
        assertEquals(4, s.size());
        assertEquals("实现要点：", s.get(0).text());
        assertEquals("第一点", s.get(1).text());
        assertEquals(List.of("docs/a.md#1"), s.get(1).citedChunkIds());
        assertEquals("第二点", s.get(2).text());
        assertEquals("编号项", s.get(3).text());
        assertEquals(List.of("b-c_d.md#10"), s.get(3).citedChunkIds());
    }

    @Test
    void markerOnlyLineAttachesToPreviousSentence() {
        List<AnswerSentence> s = parse("第一句。\n[docs/a.md#1]\n\n第二句[docs/a.md#2]");
        assertEquals(2, s.size());
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
    }

    @Test
    void codeBlockIsOneSentenceAndKeptVerbatim() {
        String text = """
                配置如下[docs/a.md#1]：
                ```yaml
                rag:
                  top-k: 5. # not a sentence end
                ```
                [docs/a.md#2]
                """;
        List<AnswerSentence> s = parse(text);
        assertEquals(2, s.size());
        assertEquals("配置如下：", s.get(0).text());
        assertEquals("rag:\n  top-k: 5. # not a sentence end", s.get(1).text());
        assertEquals(List.of("docs/a.md#2"), s.get(1).citedChunkIds());
    }

    @Test
    void mixedChineseEnglishAndFullWidthBrackets() {
        List<AnswerSentence> s = parse("使用 BM25 检索【docs/a.md#1】。Then rerank it [docs/a.md#2]. 完成！");
        assertEquals(3, s.size());
        assertEquals("使用 BM25 检索。", s.get(0).text());
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
        assertEquals("Then rerank it.", s.get(1).text());
        assertEquals("完成！", s.get(2).text());
    }

    @Test
    void hashBracketsThatAreNotChunkIdsStayAsText() {
        CitationParser.ParseResult r = CitationParser.parse(
                "用 [C#] 或 [F#] 写，见 [#1] 与 [https://x.io/a#12] 选项[docs/a.md#1]。", IDS);
        assertEquals("用 [C#] 或 [F#] 写，见 [#1] 与 [https://x.io/a#12] 选项。", r.sentences().get(0).text());
        assertEquals(List.of("docs/a.md#1"), r.sentences().get(0).citedChunkIds());
        assertEquals(0, r.unknownCitations());
    }

    @Test
    void numericFootnotesStayAsText() {
        CitationParser.ParseResult r = CitationParser.parse("见论文[1]和[2, 3]。", IDS);
        assertEquals("见论文[1]和[2, 3]。", r.sentences().get(0).text());
        assertEquals(0, r.unknownCitations());
    }

    @Test
    void inlineCodeIsNotScannedForMarkersOrSentenceEnds() {
        List<AnswerSentence> s = parse("调用 `index[docs/a.md#2]. next()` 即可[docs/a.md#1]。");
        assertEquals(1, s.size());
        assertEquals("调用 `index[docs/a.md#2]. next()` 即可。", s.get(0).text());
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
    }

    @Test
    void fullWidthSquareBrackets() {
        List<AnswerSentence> s = parse("召回分两路［docs/a.md#1］。");
        assertEquals("召回分两路。", s.get(0).text());
        assertEquals(List.of("docs/a.md#1"), s.get(0).citedChunkIds());
    }

    @Test
    void allowedIdsMayContainSpaces() {
        CitationParser.ParseResult r = CitationParser.parse("句子[my notes.md#3]。", Set.of("my notes.md#3"));
        assertEquals(List.of("my notes.md#3"), r.sentences().get(0).citedChunkIds());
    }

    @Test
    void displayTextDropsUnknownIdsAndKeepsEverythingElse() {
        CitationParser.ParseResult r = CitationParser.parse(
                "句子 [ghost.md#9]。二句[x.md#1, docs/a.md#2]。三句[docs/a.md#1]。\n```\ncode [ghost.md#9]\n```", IDS);
        assertEquals("句子。二句[docs/a.md#2]。三句[docs/a.md#1]。\n```\ncode [ghost.md#9]\n```", r.text());
        assertEquals(2, r.unknownCitations());
    }

    @Test
    void emptyAndArtifactOnlyInputs() {
        assertTrue(parse("").isEmpty());
        assertTrue(parse(null).isEmpty());
        assertTrue(parse("-\n```\n```\n---\n").isEmpty());
    }
}
