package io.github.qwzhang01.agent.core.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationClientDecoratorsTest {

    @Test
    void imageRetryRecoversFromTimeout() {
        AtomicInteger calls = new AtomicInteger();
        ImageGenerationClient flaky = request -> {
            if (calls.getAndIncrement() == 0) {
                throw new ModelException(ModelException.ErrorCode.TIMEOUT, "timeout");
            }
            return new ImageGenerationClient.ImageResult(
                    List.of(new ImageGenerationClient.GeneratedImage("https://x/a.png", null, null, "1024x1024")),
                    "mock");
        };

        var client = new RetryImageGenerationClient(flaky, 3, Duration.ZERO, 2.0);
        var result = client.generate(ImageGenerationClient.ImageGenRequest.builder().prompt("cat").build());

        assertEquals("https://x/a.png", result.images().get(0).url());
        assertEquals(2, calls.get());
    }

    @Test
    void imageRetryDoesNotRetryAuth() {
        AtomicInteger calls = new AtomicInteger();
        ImageGenerationClient bad = request -> {
            calls.incrementAndGet();
            throw new ModelException(ModelException.ErrorCode.AUTH_ERROR, "bad key");
        };

        var client = new RetryImageGenerationClient(bad, 3, Duration.ZERO, 2.0);
        assertThrows(ModelException.class, () ->
                client.generate(ImageGenerationClient.ImageGenRequest.builder().prompt("cat").build()));
        assertEquals(1, calls.get());
    }

    @Test
    void videoRetryRecoversOnStatus() {
        AtomicInteger calls = new AtomicInteger();
        VideoGenerationClient flaky = new VideoGenerationClient() {
            @Override
            public VideoTask submit(VideoGenRequest request) {
                return new VideoTask("t-1", VideoTask.STATUS_QUEUED, null, null, null, null);
            }

            @Override
            public VideoTask status(String taskId) {
                if (calls.getAndIncrement() == 0) {
                    throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR, "blip");
                }
                return new VideoTask("t-1", VideoTask.STATUS_SUCCEEDED, "https://x/v.mp4", null, 100, null);
            }
        };

        var client = new RetryVideoGenerationClient(flaky, 3, Duration.ZERO, 2.0);
        var task = client.status("t-1");
        assertEquals("https://x/v.mp4", task.videoUrl());
        assertEquals(2, calls.get());
    }

    @Test
    void imageTimeoutFires() {
        ImageGenerationClient slow = request -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ImageGenerationClient.ImageResult(List.of(), "mock");
        };

        var client = new TimeoutImageGenerationClient(slow, Duration.ofMillis(50));
        ModelException ex = assertThrows(ModelException.class, () ->
                client.generate(ImageGenerationClient.ImageGenRequest.builder().prompt("cat").build()));
        assertEquals(ModelException.ErrorCode.TIMEOUT, ex.getCode());
        client.shutdown();
    }
}
