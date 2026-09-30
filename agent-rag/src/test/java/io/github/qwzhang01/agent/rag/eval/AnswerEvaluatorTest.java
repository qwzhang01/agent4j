package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.SupportVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerEvaluatorTest {

    private static EvalCase c(String id, String type, boolean answerable) {
        return new EvalCase(id, type, "q-" + id, null, List.of(), "", answerable);
    }

    private static AnswerSentence s(SupportVerdict v) {
        return new AnswerSentence("x", List.of("a.md#1"), v, "");
    }

    private static final List<EvalCase> CASES = List.of(
            c("q1", "single_hop", true),
            c("q2", "single_hop", true),
            c("q3", "unanswerable", false),
            c("q4", "unanswerable", false),
            c("q5", "conflict", true));

    private static final Map<String, AnswerOutcome> OUTCOMES = Map.of(
            "q1", new AnswerOutcome(false, List.of(s(SupportVerdict.SUPPORTED), s(SupportVerdict.SUPPORTED),
                    s(SupportVerdict.NOT_FOUND)), "a"),
            "q2", new AnswerOutcome(true, List.of(), "资料中没有相关内容"),
            "q3", new AnswerOutcome(true, List.of(), "资料中没有相关内容"),
            "q4", new AnswerOutcome(false, List.of(s(SupportVerdict.CONTRADICTED), s(SupportVerdict.UNVERIFIED)), "b"));

    private static AnswerReport report() {
        return new AnswerEvaluator(ec -> {
            AnswerOutcome o = OUTCOMES.get(ec.id());
            if (o == null) {
                throw new IllegalStateException("pipeline down");
            }
            return o;
        }).evaluate(CASES);
    }

    @Test
    void overallMetrics() {
        AnswerReport.Summary all = report().overall();
        assertEquals(5, all.cases());
        assertEquals(2, all.refusalCorrect());
        assertEquals(0.4, all.refusalAccuracy(), 1e-9);
        assertEquals(1, all.falseRefusals());
        assertEquals(1, all.missedRefusals());
        assertEquals(1, all.errors());
        assertEquals(5, all.sentences());
        assertEquals(2.0 / 4, all.citationSupportRate(), 1e-9);
        assertEquals(2.0 / 5, all.unsupportedRatio(), 1e-9);
        assertEquals(1, all.unverified());
    }

    @Test
    void perTypeMetrics() {
        AnswerReport r = report();
        assertEquals(List.of("single_hop", "conflict", "unanswerable"), List.copyOf(r.byType().keySet()));
        AnswerReport.Summary single = r.byType().get("single_hop");
        assertEquals(0.5, single.refusalAccuracy(), 1e-9);
        assertEquals(2.0 / 3, single.citationSupportRate(), 1e-9);
        AnswerReport.Summary unans = r.byType().get("unanswerable");
        assertEquals(0.5, unans.refusalAccuracy(), 1e-9);
        assertEquals(0.0, unans.citationSupportRate(), 1e-9);
        assertTrue(Double.isNaN(r.byType().get("conflict").citationSupportRate()));
    }

    @Test
    void markdownRendersSummaryAndCases() {
        String md = report().toMarkdown("端到端");
        assertTrue(md.startsWith("# 端到端"));
        assertTrue(md.contains("| **all** | 5 | 40.0% | 1 | 1 | 5 | 50.0% | 40.0% | 1 | 1 |"));
        assertTrue(md.contains("| conflict | 1 | 0.0% |"));
        assertTrue(md.contains("pipeline down"));
    }
}
