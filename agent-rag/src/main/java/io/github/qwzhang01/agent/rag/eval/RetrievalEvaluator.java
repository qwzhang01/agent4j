package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.Retriever;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Runs a {@link Retriever} over the answerable cases that have gold refs and scores Recall@k,
 * Hit@k and MRR. Unanswerable cases and cases without gold refs are skipped. A retriever failure
 * is logged and scored as an empty ranking.
 */
public final class RetrievalEvaluator {

    /** Cut-offs used when none are given. */
    public static final List<Integer> DEFAULT_KS = List.of(1, 3, 5, 10);

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluator.class);

    private final Retriever retriever;
    private final List<Integer> ks;
    private final Function<EvalCase, String> queryFn;
    private final Map<String, String> filters;

    /** Uses {@link #DEFAULT_KS} and the raw question as query. */
    public RetrievalEvaluator(Retriever retriever) {
        this(retriever, DEFAULT_KS, EvalCase::question, Map.of());
    }

    /**
     * @param retriever retriever under test
     * @param ks        cut-offs, each &gt; 0; the retriever is asked for the largest
     * @param queryFn   query to retrieve with per case, e.g. a rewritten query for multi-turn cases;
     *                  null for the raw question
     * @param filters   metadata filters passed to every call
     */
    public RetrievalEvaluator(Retriever retriever, List<Integer> ks, Function<EvalCase, String> queryFn,
                              Map<String, String> filters) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        Objects.requireNonNull(ks, "ks");
        TreeSet<Integer> sorted = new TreeSet<>(ks);
        if (sorted.isEmpty() || sorted.first() <= 0) {
            throw new IllegalArgumentException("ks must be non-empty and positive, got: " + ks);
        }
        this.ks = List.copyOf(sorted);
        this.queryFn = queryFn == null ? EvalCase::question : queryFn;
        this.filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    public RetrievalReport evaluate(List<EvalCase> cases) {
        Objects.requireNonNull(cases, "cases");
        int maxK = ks.get(ks.size() - 1);
        List<RetrievalReport.CaseRow> rows = new ArrayList<>();
        for (EvalCase c : cases) {
            if (!c.answerable() || c.relevant().isEmpty()) {
                continue;
            }
            rows.add(evaluateCase(c, maxK));
        }
        Map<String, List<RetrievalReport.CaseRow>> grouped = new LinkedHashMap<>();
        rows.forEach(r -> grouped.computeIfAbsent(r.type(), t -> new ArrayList<>()).add(r));
        Map<String, RetrievalReport.Summary> byType = new LinkedHashMap<>();
        for (String type : Markdown.orderTypes(grouped.keySet())) {
            byType.put(type, RetrievalReport.Summary.of(grouped.get(type), ks));
        }
        long[] latencies = rows.stream().mapToLong(RetrievalReport.CaseRow::latencyMs).sorted().toArray();
        double avg = latencies.length == 0 ? 0.0 : Arrays.stream(latencies).average().orElse(0.0);
        double p95 = latencies.length == 0 ? 0.0 : latencies[(int) Math.ceil(0.95 * latencies.length) - 1];
        return new RetrievalReport(ks, RetrievalReport.Summary.of(rows, ks), byType, rows, avg, p95);
    }

    private RetrievalReport.CaseRow evaluateCase(EvalCase c, int maxK) {
        String query = queryFn.apply(c);
        if (query == null || query.isBlank()) {
            query = c.question();
        }
        List<Chunk> ranked = new ArrayList<>();
        String error = null;
        long start = System.nanoTime();
        try {
            for (ScoredChunk sc : retriever.retrieve(query, maxK, filters)) {
                ranked.add(sc.chunk());
            }
        } catch (RuntimeException e) {
            log.warn("Retrieval failed for case {}: {}", c.id(), e.toString());
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        if (ranked.size() > maxK) {
            ranked = ranked.subList(0, maxK);
        }
        Map<Integer, Double> recall = new LinkedHashMap<>();
        Map<Integer, Double> hit = new LinkedHashMap<>();
        for (int k : ks) {
            recall.put(k, RetrievalMetrics.recallAtK(ranked, c.relevant(), k));
            hit.put(k, RetrievalMetrics.hitAtK(ranked, c.relevant(), k));
        }
        List<GoldRef> found = RetrievalMetrics.found(ranked, c.relevant(), maxK);
        List<GoldRef> missed = new ArrayList<>(c.relevant());
        missed.removeAll(found);
        List<String> ids = ranked.stream().map(Chunk::chunkId).toList();
        return new RetrievalReport.CaseRow(c.id(), c.type(), query, found, missed, ids, recall, hit,
                RetrievalMetrics.reciprocalRank(ranked, c.relevant()), latencyMs, error);
    }

    /** Shortcut for {@link RetrievalReport#compareMarkdown}. */
    public static String compareMarkdown(Map<String, RetrievalReport> byConfigName) {
        return RetrievalReport.compareMarkdown(byConfigName);
    }
}
