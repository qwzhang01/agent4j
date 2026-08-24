package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * ToolExecutor boundary decorator (Stage 18 D2): measures latency, records
 * success / denial / error per tool call.
 * <p>
 * Denial detection is CONTRACT-based: the Stage 9 {@code GovernedToolExecutor}
 * blocks calls by returning text prefixed with {@code "[DENIED] "} or
 * {@code "[RATE_LIMITED] "} (the tool never runs). Those prefixes mean
 * "intercepted by governance" and count as {@code denied=true, success=false}.
 * By contrast {@code "[ERROR] ..."} (Stage 2 DefaultToolExecutor wrapping)
 * means the tool RAN and its failure text is a normal observation - that is
 * {@code success=true, denied=false}. Distinguishing them matters operationally:
 * a denied spike is a leading indicator of injection attempts (blueprint F7),
 * an error spike is a tool-quality problem.
 * <p>
 * Wiring-order contract: to see governance denials this decorator must wrap the
 * governed chain from OUTSIDE - {@code Observing(Governed(delegate))}. Wrapped
 * inside ({@code Governed(Observing(delegate))}) it only measures calls that
 * actually executed, which silently drops every denial from the metrics.
 * <p>
 * Exception discipline (same as {@link ObservingModelClient}): delegate
 * exceptions are recorded then rethrown; sink exceptions are swallowed with a
 * warning - metrics are a side channel.
 */
public final class ObservingToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ObservingToolExecutor.class);

    /** Stage 9 governance-chain denial prefixes (results, not exceptions). */
    private static final String[] GOVERNANCE_DENIAL_PREFIXES = {"[DENIED] ", "[RATE_LIMITED] "};

    private final ToolExecutor delegate;
    private final MetricsSink sink;

    private ObservingToolExecutor(ToolExecutor delegate, MetricsSink sink) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public static ObservingToolExecutor wrap(ToolExecutor delegate, MetricsSink sink) {
        return new ObservingToolExecutor(delegate, sink);
    }

    @Override
    public String execute(ToolCall toolCall) {
        long start = System.nanoTime();
        try {
            String result = delegate.execute(toolCall);
            boolean denied = isGovernanceDenial(result);
            emit(new ToolCallMetrics(toolCall.name(), elapsedMs(start), !denied, denied, null));
            return result;
        } catch (RuntimeException e) {
            emit(new ToolCallMetrics(toolCall.name(), elapsedMs(start), false, false, e.toString()));
            throw e;
        }
    }

    /** {@code true} when the result text is a governance rejection the model saw. */
    static boolean isGovernanceDenial(String result) {
        if (result == null) {
            return false;
        }
        for (String prefix : GOVERNANCE_DENIAL_PREFIXES) {
            if (result.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void emit(ToolCallMetrics metrics) {
        try {
            sink.onToolCall(metrics);
        } catch (RuntimeException e) {
            log.warn("metrics sink failed (metrics are a side channel, swallowing): {}", e.toString());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
