package io.github.qwzhang01.agent.core.client;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Timeout decorator for {@link ImageGenerationClient}.
 */
public class TimeoutImageGenerationClient implements ImageGenerationClient {

    private final ImageGenerationClient delegate;
    private final Duration timeout;
    private final ExecutorService executor;

    public TimeoutImageGenerationClient(ImageGenerationClient delegate, Duration timeout) {
        this(delegate, timeout, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "image-gen-timeout");
            t.setDaemon(true);
            return t;
        }));
    }

    public TimeoutImageGenerationClient(ImageGenerationClient delegate, Duration timeout,
                                        ExecutorService executor) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.executor = executor;
    }

    @Override
    public ImageResult generate(ImageGenRequest request) {
        return ClientTimeout.call("image.generate", () -> delegate.generate(request), timeout, executor);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
