package io.github.qwzhang01.agent.observability.metrics;

/**
 * Operations-metrics outlet (Stage 18 D1: one run, three projections).
 * <p>
 * The same decorator boundaries (Model call / Tool execution) already feed two
 * other projection systems - {@code Trajectory} (training format, Stage 14) and
 * {@code AuditEvent} (governance format, Stage 9). This sink is the third
 * projection, the operations one: its reader is the on-call engineer who asks
 * "what happened on last night's failing run - which model, how many tokens,
 * how slow, at what cost". The three projections differ by READER, not by data
 * volume; they share the same boundaries with zero duplicated instrumentation.
 * <p>
 * Implementations decide where events go: in-memory aggregation
 * ({@link MetricsCollector}), console printing, JSONL export, or (v2) an OTLP
 * adapter - see blueprint D9 for why the OpenTelemetry SDK is NOT a dependency
 * of this module: the stable interface comes first, external exporters are
 * thin adapters subscribing to it.
 * <p>
 * {@link #onAlarm} carries budget warnings (M18.2 {@code BudgetAlarmEvent}) -
 * warnings are events on the same sink, not a second notification system; it
 * is a default method so pre-M18.2 implementations keep compiling.
 */
public interface MetricsSink {

    /**
     * One model call completed (or failed) at the ModelClient boundary.
     * <p>
     * Emitted exactly once per {@code chat} call and once per consumed
     * {@code stream} (on its terminal event).
     */
    void onModelCall(ModelCallMetrics metrics);

    /**
     * One tool call completed (or failed / was denied) at the ToolExecutor
     * boundary.
     * <p>
     * Governance denials count too - a denied spike is a leading indicator of
     * prompt-injection attempts or prompt regressions (blueprint F7).
     */
    void onToolCall(ToolCallMetrics metrics);

    /**
     * One run finished and was materialized into a summary row.
     * <p>
     * Produced by {@link MetricsCollector#endRun}; consumed by external sinks
     * (console / JSONL). {@link MetricsCollector} itself ignores this method -
     * it is the producer, not a consumer.
     */
    void onRun(RunMetrics metrics);

    /**
     * A budget warning fired (WARN level, non-blocking) - the "be seen" half of
     * the M18.2 warning/blocking separation. Default no-op: pre-M18.2
     * implementations keep compiling; assemblies that care wire a sink that
     * prints, exports, or forwards to a notification system.
     */
    default void onAlarm(io.github.qwzhang01.agent.observability.cost.BudgetAlarmEvent alarm) {
    }
}
