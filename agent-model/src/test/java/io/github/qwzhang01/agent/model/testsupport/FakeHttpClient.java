package io.github.qwzhang01.agent.model.testsupport;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Stage 6.1 test support: a canned {@link HttpClient} for contract tests.
 * <p>
 * Answers EVERY request with the same status/body. Body handlers are driven
 * through their public {@code apply(ResponseInfo)} API (no reflection — JPMS
 * blocks {@code setAccessible} on {@code java.net.http} internals), fed
 * synchronously by a one-shot subscription. Works for ofString / ofLines /
 * ofInputStream alike.
 */
public final class FakeHttpClient extends HttpClient {

    private final int status;
    private final String body;

    public static FakeHttpClient ok(String body) {
        return new FakeHttpClient(200, body);
    }

    public static FakeHttpClient error(int status, String body) {
        return new FakeHttpClient(status, body);
    }

    private FakeHttpClient(int status, String body) {
        this.status = status;
        this.body = body;
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
        return new FakeResponse<>(status, bodyValue);
    }

    /** Minimal immutable fake response. */
    private record FakeResponse<T>(int status, T body) implements HttpResponse<T> {
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
            return HttpHeaders.of(Map.of("content-type",
                    List.of("text/event-stream; charset=utf-8")), (a, b) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public URI uri() {
            return URI.create("http://fake.local/v1/chat/completions");
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
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
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }

    @Override
    public Optional<java.util.concurrent.Executor> executor() {
        return Optional.empty();
    }
}
