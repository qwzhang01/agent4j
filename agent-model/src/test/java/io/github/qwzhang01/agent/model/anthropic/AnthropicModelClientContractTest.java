package io.github.qwzhang01.agent.model.anthropic;

import io.github.qwzhang01.agent.model.contract.ModelClientContract;
import io.github.qwzhang01.agent.core.client.ModelClient;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;

/**
 * Stage 6.1: Anthropic's run of the shared {@link ModelClientContract}.
 * <p>
 * Lives in the anthropic package on purpose: the contract seam constructor
 * is package-private so production code cannot touch it, and the test needs
 * it to inject a canned HttpClient. The suite pins the client's mapping
 * logic — request shaping, response parsing, error translation — against a
 * fake HTTP layer, not the live vendor.
 */
class AnthropicModelClientContractTest extends ModelClientContract {

    private static final Duration NEVER = Duration.ofSeconds(30);

    @Override
    protected String providerName() {
        return "anthropic";
    }

    @Override
    protected ModelClient clientFor(String httpBody) {
        return new AnthropicModelClient(
                "http://fake.local", "test-key", "2023-06-01", "claude-test", 64,
                NEVER, null, null, fakeClient(200, httpBody));
    }

    @Override
    protected ModelClient clientForError(int status, String body) {
        return new AnthropicModelClient(
                "http://fake.local", "test-key", "2023-06-01", "claude-test", 64,
                NEVER, null, null, fakeClient(status, body));
    }

    @Override
    protected String syncBody() {
        return """
                {
                  "id": "msg_test",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "hello from claude"}],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 12, "output_tokens": 8}
                }
                """;
    }

    @Override
    protected String toolCallBody() {
        return """
                {
                  "id": "msg_tool",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {"type": "text", "text": "let me check"},
                    {"type": "tool_use", "id": "tu_1", "name": "get_weather", "input": {"city": "SF"}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {"input_tokens": 20, "output_tokens": 10}
                }
                """;
    }

    @Override
    protected String streamBody() {
        return """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"stream"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
    }

    @Override
    protected String streamToolCallBody() {
        // Full accumulation chain: block start (id+name) -> 3 partial JSON
        // deltas -> block stop -> message_delta. Arguments are split across
        // deltas on purpose: the contract must prove accumulation.
        return """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":15}}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_stream_1","name":"get_weather"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\\"SF\\""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":",\\"unit\\":\\"c\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
    }

    /**
     * Minimal fake HttpClient answering every request with the canned
     * status/body. Body handlers that stream (ofLines / ofInputStream) get
     * a body-publisher-backed subscriber bridged via a queue, good enough
     * for the contract suite; ofString gets the raw string.
     */
    private static HttpClient fakeClient(int status, String body) {
        return new FakeHttpClient(status, body);
    }

    /** Fake HttpClient returning canned responses for all three send shapes. */
    private static final class FakeHttpClient extends HttpClient {
        private final int status;
        private final String body;

        FakeHttpClient(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> responseBodyHandler) {
            return respond(responseBodyHandler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(respond(responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture(respond(responseBodyHandler));
        }

        private <T> HttpResponse<T> respond(HttpResponse.BodyHandler<T> handler) {
            // No reflection (JPMS blocks setAccessible on java.net.http internals):
            // call the handler's public apply() with a ResponseInfo, then feed
            // the returned BodySubscriber synchronously with the canned bytes.
            HttpResponse.BodySubscriber<T> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.of("content-type",
                            List.of("text/event-stream; charset=utf-8")), (a, b) -> true);
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            });
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean finished = false;

                @Override
                public void request(long n) {
                    if (finished) {
                        return;
                    }
                    finished = true;
                    try {
                        subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
                        subscriber.onComplete();
                    } catch (Exception e) {
                        subscriber.onError(e);
                    }
                }

                @Override
                public void cancel() {
                    finished = true;
                }
            });
            T bodyValue = subscriber.getBody().toCompletableFuture().join();
            return new FakeResponse<>(status, bodyValue, uri());
        }

        private URI uri() {
            return URI.create("http://fake.local/v1/messages");
        }
    }

    /** Minimal immutable fake response. */
    private record FakeResponse<T>(int status, T body, URI uri) implements HttpResponse<T> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("text/event-stream")), (a, b) -> true);
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
