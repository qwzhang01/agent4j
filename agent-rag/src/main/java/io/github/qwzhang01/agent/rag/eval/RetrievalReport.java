package io.github.qwzhang01.agent.rag.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Result of {@link RetrievalEvaluator}.
 *
 * @param ks           cut-offs evaluated, ascending
 * @param overall      averages over all evaluated cases
 * @param byType       averages per case type, known types first
 * @param cases        one row per evaluated case, dataset order
 * @param avgLatencyMs mean retrieval latency
 * @param p95LatencyMs 95th percentile retrieval latency (nearest rank)
 */
public record RetrievalReport(
        List<Integer> ks,
        Summary overall,
        Map<String, Summary> byType,
        List<CaseRow> cases,
        double avgLatencyMs,
        double p95LatencyMs
) {
    public RetrievalReport {
        ks = List.copyOf(ks);
        Objects.requireNonNull(overall, "overall");
        byType = Collections.unmodifiableMap(new LinkedHashMap<>(byType));
        cases = List.copyOf(cases);
    }

    /**
     * Averages over a group of cases.
     *
     * @param cases  number of cases averaged
     * @param recall mean Recall@k keyed by k
     * @param hit    mean Hit@k keyed by k
     * @param mrr    mean reciprocal rank
     */
    public record Summary(int cases, Map<Integer, Double> recall, Map<Integer, Double> hit, double mrr) {
        public Summary {
            recall = Collections.unmodifiableMap(new LinkedHashMap<>(recall));
            hit = Collections.unmodifiableMap(new LinkedHashMap<>(hit));
        }

        static Summary of(List<CaseRow> rows, List<Integer> ks) {
            Map<Integer, Double> recall = new LinkedHashMap<>();
            Map<Integer, Double> hit = new LinkedHashMap<>();
            for (int k : ks) {
                recall.put(k, rows.stream().mapToDouble(r -> r.recall().get(k)).average().orElse(Double.NaN));
                hit.put(k, rows.stream().mapToDouble(r -> r.hit().get(k)).average().orElse(Double.NaN));
            }
            double mrr = rows.stream().mapToDouble(CaseRow::reciprocalRank).average().orElse(Double.NaN);
            return new Summary(rows.size(), recall, hit, mrr);
        }
    }

    /**
     * @param id             case id
     * @param type           case type
     * @param query          query sent to the retriever
     * @param found          gold refs matched within the largest k
     * @param missed         gold refs not matched within the largest k
     * @param topChunkIds    retrieved chunk ids, best first (up to the largest k)
     * @param recall         Recall@k keyed by k
     * @param hit            Hit@k keyed by k (0 or 1)
     * @param reciprocalRank 1 / rank of the first relevant chunk, 0 if none
     * @param latencyMs      retrieval wall time
     * @param error          retriever failure message, null on success (the case then scores 0)
     */
    public record CaseRow(
            String id,
            String type,
            String query,
            List<GoldRef> found,
            List<GoldRef> missed,
            List<String> topChunkIds,
            Map<Integer, Double> recall,
            Map<Integer, Double> hit,
            double reciprocalRank,
            long latencyMs,
            String error
    ) {
        public CaseRow {
            found = List.copyOf(found);
            missed = List.copyOf(missed);
            topChunkIds = List.copyOf(topChunkIds);
            recall = Collections.unmodifiableMap(new LinkedHashMap<>(recall));
            hit = Collections.unmodifiableMap(new LinkedHashMap<>(hit));
        }
    }

    /** Renders summary tables (overall + per type) and a per-case table as Markdown. */
    public String toMarkdown(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null ? "Retrieval report" : title).append("\n\n");
        long errors = cases.stream().filter(c -> c.error() != null).count();
        sb.append("- Cases: ").append(overall.cases()).append('\n')
                .append("- Latency: avg ").append(String.format(Locale.ROOT, "%.1f", avgLatencyMs))
                .append(" ms, p95 ").append(String.format(Locale.ROOT, "%.1f", p95LatencyMs)).append(" ms\n");
        if (errors > 0) {
            sb.append("- Retriever errors: ").append(errors).append('\n');
        }
        sb.append("\n## Summary\n\n");
        List<String> head = new ArrayList<>(List.of("Type", "Cases"));
        ks.forEach(k -> head.add("Recall@" + k));
        ks.forEach(k -> head.add("Hit@" + k));
        head.add("MRR");
        Markdown.header(sb, head);
        summaryRow(sb, "**all**", overall);
        byType.forEach((type, s) -> summaryRow(sb, Markdown.cell(type), s));

        int maxK = ks.isEmpty() ? 0 : ks.get(ks.size() - 1);
        sb.append("\n## Cases\n\n");
        Markdown.header(sb, List.of("ID", "Type", "Query", "Recall@" + maxK, "RR", "Found", "Missed", "Top chunks", "ms"));
        for (CaseRow c : cases) {
            Markdown.row(sb, List.of(
                    Markdown.cell(c.id()),
                    Markdown.cell(c.type()),
                    Markdown.cell(c.query()),
                    Markdown.num(c.recall().getOrDefault(maxK, 0.0)),
                    Markdown.num(c.reciprocalRank()),
                    Markdown.cell(join(c.found())),
                    Markdown.cell(c.error() != null ? "ERROR: " + c.error() : join(c.missed())),
                    Markdown.cell(String.join(", ", c.topChunkIds().subList(0, Math.min(5, c.topChunkIds().size())))),
                    String.valueOf(c.latencyMs())));
        }
        return sb.toString();
    }

    private void summaryRow(StringBuilder sb, String label, Summary s) {
        List<String> cells = new ArrayList<>(List.of(label, String.valueOf(s.cases())));
        ks.forEach(k -> cells.add(Markdown.num(s.recall().getOrDefault(k, Double.NaN))));
        ks.forEach(k -> cells.add(Markdown.num(s.hit().getOrDefault(k, Double.NaN))));
        cells.add(Markdown.num(s.mrr()));
        Markdown.row(sb, cells);
    }

    private static String join(List<GoldRef> refs) {
        List<String> parts = new ArrayList<>();
        refs.forEach(r -> parts.add(r.toString()));
        return String.join("; ", parts);
    }

    /**
     * One comparison table across retriever configurations (e.g. vector vs BM25 vs hybrid vs
     * hybrid + rerank): rows are configs, columns are Recall@k, Hit@k and MRR overall and per type.
     * {@code k} is 5 when every report evaluated it, else the largest cut-off common to all.
     *
     * @throws IllegalArgumentException when the map is empty or the reports share no cut-off
     */
    public static String compareMarkdown(Map<String, RetrievalReport> byConfigName) {
        Objects.requireNonNull(byConfigName, "byConfigName");
        if (byConfigName.isEmpty()) {
            throw new IllegalArgumentException("no reports to compare");
        }
        TreeSet<Integer> common = null;
        Set<String> types = new LinkedHashSet<>();
        for (RetrievalReport r : byConfigName.values()) {
            if (common == null) {
                common = new TreeSet<>(r.ks());
            } else {
                common.retainAll(r.ks());
            }
            types.addAll(r.byType().keySet());
        }
        if (common.isEmpty()) {
            throw new IllegalArgumentException("reports share no k cut-off");
        }
        int k = common.contains(5) ? 5 : common.last();
        List<String> orderedTypes = Markdown.orderTypes(types);

        StringBuilder sb = new StringBuilder();
        List<String> head = new ArrayList<>(List.of("Config", "Recall@" + k, "Hit@" + k, "MRR"));
        for (String t : orderedTypes) {
            head.add(Markdown.cell(t) + " R@" + k);
            head.add(Markdown.cell(t) + " H@" + k);
            head.add(Markdown.cell(t) + " MRR");
        }
        head.add("p95 ms");
        Markdown.header(sb, head);
        for (Map.Entry<String, RetrievalReport> e : byConfigName.entrySet()) {
            RetrievalReport r = e.getValue();
            List<String> cells = new ArrayList<>(List.of(Markdown.cell(e.getKey())));
            addTriple(cells, r.overall(), k);
            for (String t : orderedTypes) {
                Summary s = r.byType().get(t);
                if (s == null) {
                    cells.addAll(List.of("-", "-", "-"));
                } else {
                    addTriple(cells, s, k);
                }
            }
            cells.add(String.format(Locale.ROOT, "%.1f", r.p95LatencyMs()));
            Markdown.row(sb, cells);
        }
        return sb.toString();
    }

    private static void addTriple(List<String> cells, Summary s, int k) {
        cells.add(Markdown.num(s.recall().getOrDefault(k, Double.NaN)));
        cells.add(Markdown.num(s.hit().getOrDefault(k, Double.NaN)));
        cells.add(Markdown.num(s.mrr()));
    }
}
