package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Scores end-to-end answers: refusal accuracy (unanswerable cases should be refused, answerable
 * ones should not), citation support rate and unsupported ratio, overall and per case type.
 * An exception from the answer function is logged and the case counts as an incorrect decision
 * with no sentences.
 */
public final class AnswerEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluator.class);

    private final Function<EvalCase, AnswerOutcome> answerFn;

    /** @param answerFn answers one case, e.g. {@code c -> AnswerOutcome.of(pipeline.ask(...))} */
    public AnswerEvaluator(Function<EvalCase, AnswerOutcome> answerFn) {
        this.answerFn = Objects.requireNonNull(answerFn, "answerFn");
    }

    public AnswerReport evaluate(List<EvalCase> cases) {
        Objects.requireNonNull(cases, "cases");
        List<AnswerReport.CaseRow> rows = new ArrayList<>();
        for (EvalCase c : cases) {
            rows.add(evaluateCase(c));
        }
        Map<String, List<AnswerReport.CaseRow>> grouped = new LinkedHashMap<>();
        rows.forEach(r -> grouped.computeIfAbsent(r.type(), t -> new ArrayList<>()).add(r));
        Map<String, AnswerReport.Summary> byType = new LinkedHashMap<>();
        for (String type : Markdown.orderTypes(grouped.keySet())) {
            byType.put(type, AnswerReport.Summary.of(grouped.get(type)));
        }
        return new AnswerReport(AnswerReport.Summary.of(rows), byType, rows);
    }

    private AnswerReport.CaseRow evaluateCase(EvalCase c) {
        AnswerOutcome outcome;
        try {
            outcome = Objects.requireNonNull(answerFn.apply(c), "answer function returned null");
        } catch (RuntimeException e) {
            log.warn("Answering failed for case {}: {}", c.id(), e.toString());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new AnswerReport.CaseRow(c.id(), c.type(), c.answerable(), false, false, 0, 0, 0, 0, 0, msg);
        }
        int sup = 0;
        int con = 0;
        int nf = 0;
        int unv = 0;
        for (AnswerSentence s : outcome.sentences()) {
            switch (s.verdict()) {
                case SUPPORTED -> sup++;
                case CONTRADICTED -> con++;
                case NOT_FOUND -> nf++;
                case UNVERIFIED -> unv++;
            }
        }
        boolean correct = outcome.refused() != c.answerable();
        return new AnswerReport.CaseRow(c.id(), c.type(), c.answerable(), outcome.refused(), correct,
                outcome.sentences().size(), sup, con, nf, unv, null);
    }
}
