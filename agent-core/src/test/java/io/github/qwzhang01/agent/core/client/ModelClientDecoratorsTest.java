package io.github.qwzhang01.agent.core.client;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for ModelClient decorators: Retry, Timeout, Fallback, StructuredOutput.
 */
class ModelClientDecoratorsTest {

    // ============ RetryModelClient ============

    private static ModelResponse throwResponse(ModelException.ErrorCode code, String message) {
        // Encode error info in a special ModelResponse; CountingMock will throw it
        return new ModelResponse("__THROW__:" + code.name() + ":" + message,
                null, "__THROW__", null);
    }

    @Test
    void retry_shouldSucceedOnFirstAttempt() {
        var mock = new CountingMock();
        mock.responses.add(ModelResponse.text("ok"));

        var client = new RetryModelClient(mock, 3, Duration.ZERO, 2.0);
        ModelResponse response = client.chat(ModelRequest.builder().build());

        assertEquals("ok", response.content());
        assertEquals(1, mock.callCount.get());
    }

    @Test
    void retry_shouldRetryOnTimeoutAndSucceed() {
        var mock = new CountingMock();
        mock.responses.add(throwResponse(ModelException.ErrorCode.TIMEOUT, "timeout"));
        mock.responses.add(ModelResponse.text("recovered"));

        var client = new RetryModelClient(mock, 3, Duration.ZERO, 2.0);
        ModelResponse response = client.chat(ModelRequest.builder().build());

        assertEquals("recovered", response.content());
        assertEquals(2, mock.callCount.get());
    }

    @Test
    void retry_shouldNotRetryOnAuthError() {
        var mock = new CountingMock();
        mock.responses.add(throwResponse(ModelException.ErrorCode.AUTH_ERROR, "bad key"));

        var client = new RetryModelClient(mock, 3, Duration.ZERO, 2.0);

        assertThrows(ModelException.class, () -> client.chat(ModelRequest.builder().build()));
        assertEquals(1, mock.callCount.get());
    }

    // ============ TimeoutModelClient ============

    @Test
    void retry_shouldExhaustMaxRetries() {
        var mock = new CountingMock();
        for (int i = 0; i < 5; i++) {
            mock.responses.add(throwResponse(ModelException.ErrorCode.NETWORK_ERROR, "fail"));
        }

        var client = new RetryModelClient(mock, 2, Duration.ZERO, 2.0);

        assertThrows(ModelException.class, () -> client.chat(ModelRequest.builder().build()));
        assertEquals(3, mock.callCount.get()); // initial + 2 retries
    }

    @Test
    void timeout_shouldReturnIfFastEnough() {
        var mock = new CountingMock();
        mock.responses.add(ModelResponse.text("fast"));

        var client = new TimeoutModelClient(mock, Duration.ofSeconds(5));
        ModelResponse response = client.chat(ModelRequest.builder().build());

        assertEquals("fast", response.content());
    }

    // ============ FallbackModelClient ============

    @Test
    void timeout_shouldThrowOnExceed() {
        var mock = new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ModelResponse.text("slow");
            }

            @Override
            public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
                return java.util.stream.Stream.empty();
            }
        };

        var client = new TimeoutModelClient(mock, Duration.ofMillis(100));
        ModelException ex = assertThrows(ModelException.class,
                () -> client.chat(ModelRequest.builder().build()));
        assertEquals(ModelException.ErrorCode.TIMEOUT, ex.getCode());
    }

    @Test
    void fallback_shouldUsePrimaryWhenHealthy() {
        var primary = new CountingMock();
        primary.responses.add(ModelResponse.text("primary-ok"));

        var fallback = new CountingMock();
        fallback.responses.add(ModelResponse.text("fallback-ok"));

        var client = new FallbackModelClient(primary, fallback);
        ModelResponse response = client.chat(ModelRequest.builder().build());

        assertEquals("primary-ok", response.content());
        assertEquals(1, primary.callCount.get());
        assertEquals(0, fallback.callCount.get());
    }

    @Test
    void fallback_shouldSwitchOnPrimaryFailure() {
        var primary = new CountingMock();
        primary.responses.add(throwResponse(ModelException.ErrorCode.MODEL_ERROR, "primary down"));

        var fallback = new CountingMock();
        fallback.responses.add(ModelResponse.text("fallback-ok"));

        var client = new FallbackModelClient(primary, fallback);
        ModelResponse response = client.chat(ModelRequest.builder().build());

        assertEquals("fallback-ok", response.content());
        assertEquals(1, primary.callCount.get());
        assertEquals(1, fallback.callCount.get());
    }

    // ============ StructuredOutputModelClient ============

    @Test
    void fallback_shouldChainMultipleFallbacks() {
        var primary = new CountingMock();
        primary.responses.add(throwResponse(ModelException.ErrorCode.MODEL_ERROR, "p down"));

        var fb1 = new CountingMock();
        fb1.responses.add(throwResponse(ModelException.ErrorCode.NETWORK_ERROR, "fb1 down"));

        var fb2 = new CountingMock();
        fb2.responses.add(ModelResponse.text("fb2-ok"));

        var client = new FallbackModelClient(primary, fb1, fb2);
        ModelResponse response = client.chat(ModelRequest.builder().build());

        assertEquals("fb2-ok", response.content());
    }

    @Test
    void structuredOutput_shouldPassThroughValidJson() {
        var mock = new CountingMock();
        mock.responses.add(ModelResponse.text("{\"key\": \"value\"}"));

        var client = new StructuredOutputModelClient(mock);
        var request = ModelRequest.builder()
                .responseFormat(ModelRequest.ResponseFormat.json())
                .build();

        ModelResponse response = client.chat(request);
        assertEquals("{\"key\": \"value\"}", response.content());
        assertEquals(1, mock.callCount.get());
    }

    @Test
    void structuredOutput_shouldRetryOnInvalidJson() {
        var mock = new CountingMock();
        mock.responses.add(ModelResponse.text("not json at all"));
        mock.responses.add(ModelResponse.text("{\"valid\": true}"));

        var client = new StructuredOutputModelClient(mock, 2);
        var request = ModelRequest.builder()
                .responseFormat(ModelRequest.ResponseFormat.json())
                .build();

        ModelResponse response = client.chat(request);
        assertEquals("{\"valid\": true}", response.content());
        assertEquals(2, mock.callCount.get());
    }

    // ============ Helpers ============

    @Test
    void structuredOutput_shouldReturnErrorAfterMaxRetries() {
        var mock = new CountingMock();
        mock.responses.add(ModelResponse.text("not json"));
        mock.responses.add(ModelResponse.text("still not json"));
        mock.responses.add(ModelResponse.text("never json"));

        var client = new StructuredOutputModelClient(mock, 2);
        var request = ModelRequest.builder()
                .responseFormat(ModelRequest.ResponseFormat.json())
                .build();

        ModelResponse response = client.chat(request);
        assertEquals("error", response.finishReason());
    }

    /**
     * Mock that counts calls and consumes scripted responses.
     * If a response has finishReason "__THROW__", throws a ModelException
     * with the encoded error code and message.
     */
    static class CountingMock implements ModelClient {
        final AtomicInteger callCount = new AtomicInteger(0);
        final Queue<ModelResponse> responses = new LinkedBlockingQueue<>();

        @Override
        public ModelResponse chat(ModelRequest request) {
            callCount.incrementAndGet();
            if (responses.isEmpty()) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR, "No more responses");
            }
            ModelResponse resp = responses.poll();
            if ("__THROW__".equals(resp.finishReason())) {
                // Parse encoded error: format is "__THROW__:CODE:message"
                String encoded = resp.content();
                String[] parts = encoded.split(":", 3);
                ModelException.ErrorCode code = ModelException.ErrorCode.valueOf(parts[1]);
                throw new ModelException(code, parts[2]);
            }
            return resp;
        }

        @Override
        public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
            callCount.incrementAndGet();
            ModelResponse resp = responses.poll();
            return java.util.stream.Stream.of(new StreamEvent.Done(resp));
        }
    }
}
