package io.github.qwzhang01.agent.observability.cost;

import io.github.qwzhang01.agent.observability.metrics.MetricsSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-dimensional budget ledger (Stage 18 M18.2): pre-flight gate + honest
 * post-hoc accounting, the five-dimension generalization of the Stage 15
 * CostLedger pattern (blueprint D4: observability is the layer UNDER the
 * enterprise domain - the enterprise CostLedger stays untouched).
 * <p>
 * Two-phase discipline (blueprint D3):
 * <ul>
 *   <li>{@link #requireBudget} - the fast pre-flight gate: "used + this call's
 *       estimate &gt; limit" is {@link BudgetCheck.Denied} (fail-closed, the
 *       conservative tail of the budget may go unused - that is the price of
 *       never overdrafting); "already used &gt;= warnAtPercent" is
 *       {@link BudgetCheck.Warn} (alarm event emitted, call proceeds)</li>
 *   <li>{@link #recordUsage} - the honest ledger: real usage replaces the
 *       estimate after the fact; the estimate/actual gap is kept, not smoothed</li>
 * </ul>
 * <p>
 * Unconfigured (dimension, key) pairs are UNLIMITED: requireBudget returns Ok
 * and {@link #limitOf} reports -1 (the same placeholder convention as Stage 12
 * {@code ServiceAccount.UNLIMITED_BUDGET}); usage is still counted for them so
 * the dashboard can show spend before a cap is decided.
 * <p>
 * Warn alarms are emitted EVERY time the warning line is crossed - v1 has no
 * rate limiting (the Stage 12 NoisePolicy lesson applies to notification
 * fan-out, which is the alarm sink's concern, not the ledger's).
 */
public final class BudgetBook {

    private static final Logger log = LoggerFactory.getLogger(BudgetBook.class);

    private final Map<BudgetDimension, Map<String, Long>> limits = new HashMap<>();
    private final Map<BudgetDimension, Map<String, Long>> used = new HashMap<>();
    private final int warnAtPercent;
    private final MetricsSink alarmSink;

    private BudgetBook(Builder builder) {
        this.limits.putAll(builder.limits);
        this.warnAtPercent = builder.warnAtPercent;
        this.alarmSink = builder.alarmSink;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ============ Phase 1: pre-flight gate ============

    /**
     * Pre-flight check: may this call proceed under (dimension, key)?
     * <p>
     * DENIED is decided on the PROJECTION (used + estimate &gt; limit); WARN on
     * what has ALREADY been used (used &gt;= warnAtPercent of limit). A call that
     * exactly exhausts the budget (used + est == limit) is allowed - denial is
     * for overdraft, not for landing exactly on the line.
     *
     * @param estimatedTokens this call's token estimate (request size approximation)
     */
    public synchronized BudgetCheck requireBudget(BudgetDimension dimension, String key,
                                                  long estimatedTokens) {
        Objects.requireNonNull(dimension, "dimension");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
        Long limit = limits.getOrDefault(dimension, Map.of()).get(key);
        if (limit == null) {
            return new BudgetCheck.Ok();  // unlimited by absence
        }
        long usedNow = usedOf(dimension, key);
        if (usedNow + estimatedTokens > limit) {
            return new BudgetCheck.Denied(usedNow, limit);
        }
        int percent = (int) (usedNow * 100 / limit);
        if (percent >= warnAtPercent) {
            emitAlarm(new BudgetAlarmEvent(dimension, key, usedNow, limit, percent));
            return new BudgetCheck.Warn(percent, usedNow, limit);
        }
        return new BudgetCheck.Ok();
    }

    // ============ Phase 2: honest ledger ============

    /** Record actual usage (replace-the-estimate accounting). Unconfigured keys are counted too. */
    public synchronized void recordUsage(BudgetDimension dimension, String key, long actualTokens) {
        Objects.requireNonNull(dimension, "dimension");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        if (actualTokens < 0) {
            throw new IllegalArgumentException("actualTokens must not be negative");
        }
        used.computeIfAbsent(dimension, d -> new HashMap<>()).merge(key, actualTokens, Long::sum);
    }

    // ============ Queries ============

    /** Tokens recorded so far against (dimension, key); 0 when nothing recorded. */
    public synchronized long usedOf(BudgetDimension dimension, String key) {
        return used.getOrDefault(dimension, Map.of()).getOrDefault(key, 0L);
    }

    /** Configured limit, or -1 when unlimited (ServiceAccount placeholder convention). */
    public synchronized long limitOf(BudgetDimension dimension, String key) {
        Long limit = limits.getOrDefault(dimension, Map.of()).get(key);
        return limit == null ? -1L : limit;
    }

    /**
     * Remaining tokens: {@code max(0, limit - used)}; {@link Long#MAX_VALUE} when
     * unlimited (safe for routing arithmetic - never triggers a downgrade).
     */
    public synchronized long remainingOf(BudgetDimension dimension, String key) {
        Long limit = limits.getOrDefault(dimension, Map.of()).get(key);
        if (limit == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, limit - usedOf(dimension, key));
    }

    // ============ Internals ============

    private void emitAlarm(BudgetAlarmEvent alarm) {
        if (alarmSink == null) {
            return;
        }
        try {
            alarmSink.onAlarm(alarm);
        } catch (RuntimeException e) {
            log.warn("alarm sink failed (alarms are a side channel, swallowing): {}", e.toString());
        }
    }

    public static final class Builder {
        private final Map<BudgetDimension, Map<String, Long>> limits = new HashMap<>();
        private int warnAtPercent = 80;
        private MetricsSink alarmSink;

        /**
         * Configure a budget. Absent configuration means unlimited - a limit of
         * 0 or less is rejected (fail-fast at assembly, not at first check).
         */
        public Builder budget(BudgetDimension dimension, String key, long limitTokens) {
            Objects.requireNonNull(dimension, "dimension");
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be null or blank");
            }
            if (limitTokens <= 0) {
                throw new IllegalArgumentException(
                        "limitTokens must be positive (unlimited = do not configure): " + key);
            }
            limits.computeIfAbsent(dimension, d -> new HashMap<>()).put(key, limitTokens);
            return this;
        }

        /** Warning line in percent of the limit (1-99, default 80). */
        public Builder warnAtPercent(int percent) {
            if (percent < 1 || percent > 99) {
                throw new IllegalArgumentException("warnAtPercent must be within 1-99: " + percent);
            }
            this.warnAtPercent = percent;
            return this;
        }

        /** Optional sink receiving {@link BudgetAlarmEvent}s when WARN is crossed. */
        public Builder alarmSink(MetricsSink sink) {
            this.alarmSink = sink;
            return this;
        }

        public BudgetBook build() {
            return new BudgetBook(this);
        }
    }
}
