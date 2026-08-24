package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * ModelClient boundary decorator (Stage 18 D2): measures latency, reads usage,
 * records finish reason - the operations projection of every model call.
 * <p>
 * Fourth generation of the decorator lineage (capability at the boundary, the
 * path stays dumb): Retry/Timeout/Fallback (Stage 1, availability) ->
 * GovernedToolExecutor (Stage 9, governance) -> RecordingModelClient (Stage 14,
 * training data) -> ObservingModelClient (Stage 18, operations metrics).
 * {@code ReActAgentLoop} is not touched - one line of wiring gives metrics to
 * every existing agent.
 * <p>
 * Two exception directions, deliberately different (javadoc contract):
 * <ul>
 *   <li>delegate exceptions: recorded as {@code error} metrics, then RETHROWN -
 *       business semantics stay faithful (same discipline as Stage 14
 *       RecordingModelClient: record, never swallow)</li>
 *   <li>sink exceptions: caught and logged - metrics are a side channel and must
 *       never break the run they observe (same discipline as Stage 12 listener
 *       isolation)</li>
 * </ul>
 * <p>
 * Streaming: metrics are emitted exactly once on the terminal event
 * ({@link StreamEvent.Done} or {@link StreamEvent.Error}) of a CONSUMED stream;
 * latency spans from the {@code stream()} call to the terminal event. A stream
 * abandoned without a terminal event emits nothing (an unconsumed call never
 * happened - lazy semantics preserved).
 * <p>
 * Recommended wiring: OUTERMOST model layer ({@code Observing(Routing(Fallback(...)))})
 * so latency includes inner decorators' retry/timeout overhead - that is the
 * latency the caller actually perceived.
 */
public final class ObservingModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(ObservingModelClient.class);

    private final ModelClient delegate;
    private final MetricsSink sink;

    private ObservingModelClient(ModelClient delegate, MetricsSink sink) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public static ObservingModelClient wrap(ModelClient delegate, MetricsSink sink) {
        return new ObservingModelClient(delegate, sink);
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        long start = System.nanoTime();
        try {
            ModelResponse response = delegate.chat(request);
            emit(ModelCallMetrics.from(request.model(), response, elapsedMs(start)));
            return response;
        } catch (RuntimeException e) {
            emit(ModelCallMetrics.failure(request.model(), elapsedMs(start), e.toString()));
            throw e;
        }
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        long start = System.nanoTime();
        AtomicBoolean emitted = new AtomicBoolean(false);
        return delegate.stream(request).peek(event -> {
            if (event instanceof StreamEvent.Done done && !emitted.get()) {
                emitted.set(true);
                emit(ModelCallMetrics.from(request.model(), done.finalResponse(), elapsedMs(start)));
            } else if (event instanceof StreamEvent.Error err && !emitted.get()) {
                emitted.set(true);
                emit(ModelCallMetrics.failure(request.model(), elapsedMs(start), err.message()));
            }
            // non-terminal events: wait for Done/Error; nothing emitted yet
        });
    }

    /** Side-channel failure must not break the observed call. */
    private void emit(ModelCallMetrics metrics) {
        try {
            sink.onModelCall(metrics);
        } catch (RuntimeException e) {
            log.warn("metrics sink failed (metrics are a side channel, swallowing): {}", e.toString());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
