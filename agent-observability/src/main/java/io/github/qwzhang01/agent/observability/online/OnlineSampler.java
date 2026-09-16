package io.github.qwzhang01.agent.observability.online;

import io.github.qwzhang01.agent.observability.metrics.RunMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Online-traffic sampler (Stage 7.3 "线上采样，不默认保存所有敏感内容"):
 * keep a bounded, sampled window of run rows for online evaluation, with
 * prompt/answer TEXT dropped by default - only structural fields ride in
 * (RunMetrics already is structural: counts, durations, versions ride in
 * the RunRecord, not here).
 * <p>
 * Sampling decisions:
 * <ul>
 *   <li>deterministic 1-in-N sampling (runId hash) - every deployment sees
 *       the same sample for the same traffic, no Random-state nondeterminism
 *       in eval inputs</li>
 *   <li>ALWAYS keep failed runs: failures are the scarce signal; sampling
 *       them away would bias completion rate upward (survivor bias)</li>
 *   <li>capability: keep a deep slice of a run's structural trace for
 *       replay diagnostics (bounded; content already structural)</li>
 * </ul>
 * Bounded memory: the window keeps at most {@code maxRows} rows (ring
 * eviction, oldest first). This class stores NO free text at all - the
 * sampled RunMetrics is already the redacted projection.
 */
public final class OnlineSampler {

    private final int oneInN;
    private final int maxRows;
    private final List<RunMetrics> window = new ArrayList<>();
    private long offered;
    private long keptFailures;
    private long sampledSuccesses;

    /**
     * @param oneInN  sample every n-th run (1 = keep everything)
     * @param maxRows bounded window size
     */
    public OnlineSampler(int oneInN, int maxRows) {
        if (oneInN < 1) {
            throw new IllegalArgumentException("oneInN must be >= 1: " + oneInN);
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be >= 1: " + maxRows);
        }
        this.oneInN = oneInN;
        this.maxRows = maxRows;
    }

    /** Deterministic 1-in-N sample, failures always kept. */
    public synchronized void offer(RunMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        offered++;
        boolean keep = !metrics.succeeded()  // failures are the scarce signal
                || sampleHit(metrics.runId());
        if (!keep) {
            return;
        }
        if (!metrics.succeeded()) {
            keptFailures++;
        } else {
            sampledSuccesses++;
        }
        if (window.size() == maxRows) {
            window.remove(0);  // ring eviction, oldest first
        }
        window.add(metrics);
    }

    private boolean sampleHit(String runId) {
        if (oneInN == 1) {
            return true;
        }
        // deterministic: same runId -> same verdict, no RNG state
        return Math.floorMod(runId.hashCode(), oneInN) == 0;
    }

    /** The current sampled window (defensive copy). */
    public synchronized List<RunMetrics> window() {
        return List.copyOf(window);
    }

    /** Aggregate the current window into the online metrics row. */
    public synchronized Optional<OnlineMetrics> snapshot() {
        if (window.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(OnlineMetrics.from(window));
    }

    /** Runs offered to the sampler so far (the denominator of coverage). */
    public synchronized long offered() {
        return offered;
    }

    /** Failed runs kept despite sampling (survivor-bias guard). */
    public synchronized long keptFailures() {
        return keptFailures;
    }

    /** Successful runs the sample kept. */
    public synchronized long sampledSuccesses() {
        return sampledSuccesses;
    }
}
