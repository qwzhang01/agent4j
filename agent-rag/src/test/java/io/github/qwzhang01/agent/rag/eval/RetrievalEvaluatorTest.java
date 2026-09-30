package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.Retriever;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ConversationTurn;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.qwzhang01.agent.rag.eval.RetrievalMetricsTest.chunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvaluatorTest {

    private static final List<EvalCase> CASES = List.of(
            new EvalCase("q1", "single_hop", "召回", null, List.of(new GoldRef("a.md", "召回")), "", true),
            new EvalCase("q2", "single_hop", "重排", null, List.of(new GoldRef("b.md", null)), "", true),
            new EvalCase("q3", "multi_turn", "那它呢", List.of(ConversationTurn.user("融合")),
                    List.of(new GoldRef("c.md", null), new GoldRef("d.md", null)), "", true),
            new EvalCase("q4", "unanswerable", "股价", null, List.of(), "", false),
            new EvalCase("q5", "multi_hop", "boom", null, List.of(new GoldRef("a.md", null)), "", true));

    /** Returns a fixed ranking per query; fails on "boom". */
    private static final class FakeRetriever implements Retriever {
        final Map<String, List<Chunk>> byQuery;
        final List<String> queries = new ArrayList<>();

        FakeRetriever(Map<String, List<Chunk>> byQuery) {
            this.byQuery = byQuery;
        }

        @Override
        public List<ScoredChunk> retrieve(String query, int topK, Map<String, String> filters) {
            queries.add(query + "@" + topK);
            if (query.equals("boom")) {
                throw new IllegalStateException("index down");
            }
            List<ScoredChunk> out = new ArrayList<>();
            for (Chunk c : byQuery.getOrDefault(query, List.of())) {
                out.add(ScoredChunk.of(c, ScoredChunk.BM25, 1.0));
            }
            return out;
        }
    }

    private static FakeRetriever retriever() {
        return new FakeRetriever(Map.of(
                "召回", List.of(chunk("a.md", 1, "记忆召回"), chunk("x.md", 2)),
                "重排", List.of(chunk("x.md", 1), chunk("y.md", 2), chunk("b.md", 3)),
                "融合 那它呢", List.of(chunk("c.md", 1), chunk("z.md", 1), chunk("z.md", 2), chunk("z.md", 3),
                        chunk("z.md", 4), chunk("d.md", 9))));
    }

    @Test
    void computesOverallPerTypeAndRows() {
        FakeRetriever r = retriever();
        RetrievalEvaluator evaluator = new RetrievalEvaluator(r, List.of(5, 1, 3),
                c -> c.history().isEmpty() ? c.question() : c.history().get(0).content() + " " + c.question(), null);
        RetrievalReport report = evaluator.evaluate(CASES);

        assertEquals(List.of(1, 3, 5), report.ks());
        assertEquals(4, report.overall().cases());
        assertTrue(r.queries.contains("融合 那它呢@5"));

        RetrievalReport.Summary single = report.byType().get("single_hop");
        assertEquals(2, single.cases());
        assertEquals(0.5, single.recall().get(1));
        assertEquals(1.0, single.recall().get(3));
        assertEquals((1.0 + 1.0 / 3) / 2, single.mrr(), 1e-9);

        RetrievalReport.Summary multiTurn = report.byType().get("multi_turn");
        assertEquals(0.5, multiTurn.recall().get(5));
        assertEquals(1.0, multiTurn.hit().get(1));

        assertEquals(List.of("single_hop", "multi_turn", "multi_hop"), List.copyOf(report.byType().keySet()));
        assertEquals(2.5 / 4, report.overall().recall().get(5), 1e-9);

        RetrievalReport.CaseRow q3 = report.cases().get(2);
        assertEquals("融合 那它呢", q3.query());
        assertEquals(List.of(new GoldRef("c.md", null)), q3.found());
        assertEquals(List.of(new GoldRef("d.md", null)), q3.missed());
        assertEquals(5, q3.topChunkIds().size());
        assertNull(q3.error());

        RetrievalReport.CaseRow q5 = report.cases().get(3);
        assertNotNull(q5.error());
        assertEquals(0.0, q5.reciprocalRank());
        assertTrue(report.p95LatencyMs() >= 0);
    }

    @Test
    void markdownContainsTablesAndTypes() {
        RetrievalReport report = new RetrievalEvaluator(retriever()).evaluate(CASES);
        String md = report.toMarkdown("BM25 基线");
        assertTrue(md.startsWith("# BM25 基线"));
        assertTrue(md.contains("| Type | Cases | Recall@1 | Recall@3 | Recall@5 | Recall@10 |"));
        assertTrue(md.contains("| **all** | 4 |"));
        assertTrue(md.contains("| single_hop | 2 |"));
        assertTrue(md.contains("| q3 | multi_turn | 那它呢 |"));
        assertTrue(md.contains("ERROR: index down"));
    }

    @Test
    void markdownEscapesPipesAndNewlinesInCells() {
        List<EvalCase> cases = List.of(new EvalCase("q|1", "single_hop", "a | b\nc",
                null, List.of(new GoldRef("a.md", null)), "", true));
        String md = new RetrievalEvaluator(retriever()).evaluate(cases).toMarkdown(null);
        assertTrue(md.contains("| q\\|1 | single_hop | a \\| b c |"), md);
    }

    @Test
    void compareTableHasRowPerConfigAndTypeColumns() {
        RetrievalReport bm25 = new RetrievalEvaluator(retriever()).evaluate(CASES);
        RetrievalReport empty = new RetrievalEvaluator(new FakeRetriever(Map.of())).evaluate(CASES);
        Map<String, RetrievalReport> byConfig = new LinkedHashMap<>();
        byConfig.put("纯向量", empty);
        byConfig.put("BM25", bm25);
        String md = RetrievalEvaluator.compareMarkdown(byConfig);
        String[] lines = md.split("\n");
        assertEquals(4, lines.length);
        assertTrue(lines[0].startsWith("| Config | Recall@5 | Hit@5 | MRR | single_hop R@5 | single_hop H@5 | single_hop MRR | multi_turn R@5"));
        assertTrue(lines[2].startsWith("| 纯向量 | 0.000 | 0.000 | 0.000 |"));
        assertTrue(lines[3].startsWith("| BM25 | 0.500 | 0.500 |"));
    }

    @Test
    void compareFallsBackToLargestCommonK() {
        RetrievalReport a = new RetrievalEvaluator(retriever(), List.of(1, 3), null, Map.of()).evaluate(CASES);
        RetrievalReport b = new RetrievalEvaluator(retriever(), List.of(3, 10), null, Map.of()).evaluate(CASES);
        assertTrue(RetrievalReport.compareMarkdown(Map.of("a", a, "b", b)).contains("Recall@3"));
        RetrievalReport c = new RetrievalEvaluator(retriever(), List.of(10), null, Map.of()).evaluate(CASES);
        assertThrows(IllegalArgumentException.class, () -> RetrievalReport.compareMarkdown(Map.of("a", a, "c", c)));
        assertThrows(IllegalArgumentException.class, () -> RetrievalReport.compareMarkdown(Map.of()));
    }
}
