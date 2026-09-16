package io.github.qwzhang01.agent.observability.ops;

import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.version.RunRecord;
import io.github.qwzhang01.agent.observability.version.RunRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Anomaly localization (Stage 7.4 "指标异常可以定位到具体 Run、Step、Tool、
 * Provider 和版本"): given a drifted metric window, walk DOWN the evidence
 * tree the registry already holds - which runs failed, which combination
 * served them, which tools were denied - and produce the ranked suspect
 * list. No new data structures: this is a query over RunRecords.
 * <p>
 * Ranking logic (v1, honest and simple): suspects are ordered by
 * contribution - the combination serving the most failed runs first, the
 * tools with the most denials next, provider errors last (they carry no
 * model id in the run row yet, a known gap).
 */
public final class AnomalyLocalizer {

    private AnomalyLocalizer() {
    }

    /** One ranked suspect with its evidence. */
    public record Suspect(
            String dimension,   // "version" / "tool" / "provider"
            String identity,
            int failedRuns,
            String evidence) {
    }

    /**
     * Localize a bad window: the failed runs of the given rows, grouped and
     * ranked by what they share.
     *
     * @param rows     the window's run rows (from the sampler or registry)
     * @param registry the version registry (combination attribution)
     * @return ranked suspects, most contributing first; empty when every
     *         run succeeded (nothing to localize)
     */
    public static List<Suspect> localize(List<RunMetrics> rows, RunRegistry registry) {
        Objects.requireNonNull(rows, "rows");
        List<RunMetrics> failed = new ArrayList<>();
        for (RunMetrics m : rows) {
            if (!m.succeeded()) {
                failed.add(m);
            }
        }
        if (failed.isEmpty()) {
            return List.of();  // a healthy window has no suspects
        }

        List<Suspect> suspects = new ArrayList<>();

        // 1. version combinations serving failed runs (the reproducibility axis)
        if (registry != null) {
            record ComboCount(String combination, int failedRuns) {
            }
            List<ComboCount> combos = new ArrayList<>();
            for (RunMetrics m : failed) {
                String combo = registry.byRunId(m.runId())
                        .map(RunRecord::combination)
                        .orElse("(no version recorded)");
                boolean found = false;
                for (int i = 0; i < combos.size(); i++) {
                    if (combos.get(i).combination().equals(combo)) {
                        combos.set(i, new ComboCount(combo, combos.get(i).failedRuns() + 1));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    combos.add(new ComboCount(combo, 1));
                }
            }
            for (ComboCount c : combos) {
                suspects.add(new Suspect(
                        "version", c.combination(), c.failedRuns(),
                        c.failedRuns() + " failed run(s) served by this combination"));
            }
        }

        // 2. denied tools across the failed runs (the injection/policy axis)
        long totalDenied = failed.stream().mapToLong(RunMetrics::deniedToolCalls).sum();
        if (totalDenied > 0) {
            suspects.add(new Suspect(
                    "tool", "(denied calls)", (int) totalDenied,
                    totalDenied + " denied tool call(s) across " + failed.size()
                            + " failed run(s) - review denial reasons"));
        }

        // 3. provider errors (the model axis; no model id in the row yet, honest gap)
        long modelErrors = failed.stream().mapToLong(RunMetrics::modelCallErrors).sum();
        if (modelErrors > 0) {
            suspects.add(new Suspect(
                    "provider", "(model errors)", (int) modelErrors,
                    modelErrors + " model call error(s) across " + failed.size()
                            + " failed run(s) - check the provider error series by model id"));
        }

        // rank: failed-run contribution descending, dimension tiebreak
        suspects.sort((a, b) -> {
            int byCount = Integer.compare(b.failedRuns(), a.failedRuns());
            return byCount != 0 ? byCount : a.dimension().compareTo(b.dimension());
        });
        return suspects;
    }
}
