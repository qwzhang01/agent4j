package io.github.qwzhang01.agent.rag.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of {@link AnswerEvaluator}.
 *
 * @param overall totals over all cases
 * @param byType  totals per case type, known types first
 * @param cases   one row per case, dataset order
 */
public record AnswerReport(Summary overall, Map<String, Summary> byType, List<CaseRow> cases) {

    public AnswerReport {
        Objects.requireNonNull(overall, "overall");
        byType = Collections.unmodifiableMap(new LinkedHashMap<>(byType));
        cases = List.copyOf(cases);
    }

    /**
     * @param cases            number of cases
     * @param refusalCorrect   cases whose refuse/answer decision matched {@code answerable}
     * @param falseRefusals    answerable cases that were refused
     * @param missedRefusals   unanswerable cases that were answered
     * @param errors           cases whose answer function threw (counted as incorrect, no sentences)
     * @param sentences        answer sentences over all cases
     * @param supported        sentences judged SUPPORTED
     * @param contradicted     sentences judged CONTRADICTED
     * @param notFound         sentences judged NOT_FOUND
     * @param unverified       sentences left UNVERIFIED
     */
    public record Summary(int cases, int refusalCorrect, int falseRefusals, int missedRefusals, int errors,
                          int sentences, int supported, int contradicted, int notFound, int unverified) {

        /** Correct refuse/answer decisions over all cases; NaN when there are no cases. */
        public double refusalAccuracy() {
            return cases == 0 ? Double.NaN : (double) refusalCorrect / cases;
        }

        /** SUPPORTED over verified sentences (SUPPORTED + CONTRADICTED + NOT_FOUND); NaN when none. */
        public double citationSupportRate() {
            int verified = supported + contradicted + notFound;
            return verified == 0 ? Double.NaN : (double) supported / verified;
        }

        /** (NOT_FOUND + CONTRADICTED) over all sentences; NaN when there are none. */
        public double unsupportedRatio() {
            return sentences == 0 ? Double.NaN : (double) (notFound + contradicted) / sentences;
        }

        static Summary of(List<CaseRow> rows) {
            int correct = 0;
            int falseRef = 0;
            int missedRef = 0;
            int errors = 0;
            int sentences = 0;
            int sup = 0;
            int con = 0;
            int nf = 0;
            int unv = 0;
            for (CaseRow r : rows) {
                if (r.refusalCorrect()) {
                    correct++;
                } else if (r.error() == null && r.answerable()) {
                    falseRef++;
                } else if (r.error() == null) {
                    missedRef++;
                }
                if (r.error() != null) {
                    errors++;
                }
                sentences += r.sentences();
                sup += r.supported();
                con += r.contradicted();
                nf += r.notFound();
                unv += r.unverified();
            }
            return new Summary(rows.size(), correct, falseRef, missedRef, errors, sentences, sup, con, nf, unv);
        }
    }

    /**
     * @param id             case id
     * @param type           case type
     * @param answerable     gold answerability
     * @param refused        system refused
     * @param refusalCorrect {@code refused == !answerable} and no error
     * @param sentences      number of answer sentences
     * @param supported      SUPPORTED sentences
     * @param contradicted   CONTRADICTED sentences
     * @param notFound       NOT_FOUND sentences
     * @param unverified     UNVERIFIED sentences
     * @param error          answer function failure message, null on success
     */
    public record CaseRow(String id, String type, boolean answerable, boolean refused, boolean refusalCorrect,
                          int sentences, int supported, int contradicted, int notFound, int unverified,
                          String error) {
    }

    /** Renders a summary table (overall + per type) and a per-case table as Markdown. */
    public String toMarkdown(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null ? "Answer report" : title).append("\n\n");
        sb.append("## Summary\n\n");
        Markdown.header(sb, List.of("Type", "Cases", "Refusal acc", "False refusals", "Missed refusals",
                "Sentences", "Support rate", "Unsupported ratio", "Unverified", "Errors"));
        summaryRow(sb, "**all**", overall);
        byType.forEach((type, s) -> summaryRow(sb, Markdown.cell(type), s));
        sb.append("\n## Cases\n\n");
        Markdown.header(sb, List.of("ID", "Type", "Answerable", "Refused", "Refusal ok",
                "Sentences", "Supported", "Contradicted", "Not found", "Unverified", "Error"));
        for (CaseRow c : cases) {
            Markdown.row(sb, List.of(
                    Markdown.cell(c.id()), Markdown.cell(c.type()),
                    String.valueOf(c.answerable()), String.valueOf(c.refused()),
                    c.refusalCorrect() ? "yes" : "**no**",
                    String.valueOf(c.sentences()), String.valueOf(c.supported()),
                    String.valueOf(c.contradicted()), String.valueOf(c.notFound()),
                    String.valueOf(c.unverified()), Markdown.cell(c.error())));
        }
        return sb.toString();
    }

    private static void summaryRow(StringBuilder sb, String label, Summary s) {
        List<String> cells = new ArrayList<>(List.of(label, String.valueOf(s.cases()),
                Markdown.pct(s.refusalAccuracy()), String.valueOf(s.falseRefusals()),
                String.valueOf(s.missedRefusals()), String.valueOf(s.sentences()),
                Markdown.pct(s.citationSupportRate()), Markdown.pct(s.unsupportedRatio()),
                String.valueOf(s.unverified()), String.valueOf(s.errors())));
        Markdown.row(sb, cells);
    }
}
