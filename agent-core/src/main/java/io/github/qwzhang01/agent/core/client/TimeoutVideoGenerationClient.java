package io.github.qwzhang01.agent.core.client;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Timeout decorator for {@link VideoGenerationClient} HTTP calls.
 * {@link #awaitCompletion} keeps the delegate's own poll timeout.
 */
public class TimeoutVideoGenerationClient implements VideoGenerationClient {

    private final VideoGenerationClient delegate;
    private final Duration timeout;
    private final ExecutorService executor;

    public TimeoutVideoGenerationClient(VideoGenerationClient delegate, Duration timeout) {
        this(delegate, timeout, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "video-gen-timeout");
            t.setDaemon(true);
            return t;
        }));
    }

    public TimeoutVideoGenerationClient(VideoGenerationClient delegate, Duration timeout,
                                        ExecutorService executor) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.executor = executor;
    }

    @Override
    public VideoTask submit(VideoGenRequest request) {
        return ClientTimeout.call("video.submit", () -> delegate.submit(request), timeout, executor);
    }

    @Override
    public VideoTask status(String taskId) {
        return ClientTimeout.call("video.status", () -> delegate.status(taskId), timeout, executor);
    }

    @Override
    public byte[] downloadContent(String taskId) {
        return ClientTimeout.call("video.download", () -> delegate.downloadContent(taskId), timeout, executor);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
