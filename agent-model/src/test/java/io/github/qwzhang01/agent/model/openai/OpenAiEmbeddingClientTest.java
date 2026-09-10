package io.github.qwzhang01.agent.model.openai;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.model.testsupport.MockApiServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol-format tests for {@link OpenAiEmbeddingClient} against a scripted
 * mock server: request wire format, single/batch responses, provider-side
 * index shuffling, and the HTTP-status-to-ErrorCode mapping.
 * <p>
 * No live API is called; vectors are fixtures, not semantic assertions.
 */
class OpenAiEmbeddingClientTest {

    @Test
    void embed_sendsExpectedWireFormat_andParsesVector() throws Exception {
        try (MockApiServer api = new MockApiServer()) {
            api.enqueue("/embeddings", """
                    {"data":[{"index":0,"embedding":[0.12,-0.34,0.56]}]}
                    """);
            OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                    api.baseUrl(), "test-key", "text-embedding-3-small");

            float[] vector = client.embed("user likes black coffee");

            assertArrayEquals(new float[]{0.12f, -0.34f, 0.56f}, vector, 1e-6f);

            String body = api.capturedBody("/embeddings");
            assertTrue(body.contains("\"model\":\"text-embedding-3-small\""), "model sent: " + body);
            assertTrue(body.contains("\"input\":[\"user likes black coffee\"]"), "input sent: " + body);
            assertEquals(1, api.requestCount("/embeddings"));
        }
    }

    @Test
    void embedAll_batchRequest_returnsInputAligned() throws Exception {
        try (MockApiServer api = new MockApiServer()) {
            // Provider deliberately returns items out of order; client must re-order by index.
            api.enqueue("/embeddings", """
                    {"data":[
                      {"index":1,"embedding":[0.0,1.0]},
                      {"index":0,"embedding":[1.0,0.0]},
                      {"index":2,"embedding":[1.0,1.0]}
                    ]}
                    """);
            OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                    api.baseUrl(), "test-key", "text-embedding-3-small");

            var result = client.embedAll(java.util.List.of("a", "b", "c"));

            assertEquals(3, result.size());
            assertArrayEquals(new float[]{1.0f, 0.0f}, result.get(0), 1e-6f);
            assertArrayEquals(new float[]{0.0f, 1.0f}, result.get(1), 1e-6f);
            assertArrayEquals(new float[]{1.0f, 1.0f}, result.get(2), 1e-6f);
            assertEquals(1, api.requestCount("/embeddings"), "batch must be a single request");
        }
    }

    @Test
    void embed_apiError_mapsToModelException() throws Exception {
        try (MockApiServer api = new MockApiServer()) {
            api.enqueue("/embeddings", 401, "{\"error\":\"bad key\"}");
            OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                    api.baseUrl(), "test-key", "text-embedding-3-small");

            ModelException ex = assertThrows(ModelException.class, () -> client.embed("x"));
            assertEquals(ModelException.ErrorCode.AUTH_ERROR, ex.getCode());
        }
    }

    @Test
    void embed_rateLimit_mapsToRateLimited() throws Exception {
        try (MockApiServer api = new MockApiServer()) {
            api.enqueue("/embeddings", 429, "{\"error\":\"slow down\"}");
            OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                    api.baseUrl(), "test-key", "text-embedding-3-small");

            ModelException ex = assertThrows(ModelException.class, () -> client.embed("x"));
            assertEquals(ModelException.ErrorCode.RATE_LIMITED, ex.getCode());
        }
    }

    @Test
    void embed_blankText_rejectedBeforeHttpCall() throws Exception {
        try (MockApiServer api = new MockApiServer()) {
            OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                    api.baseUrl(), "test-key", "text-embedding-3-small");

            assertThrows(IllegalArgumentException.class, () -> client.embed("  "));
            assertEquals(0, api.requestCount("/embeddings"), "no HTTP call for blank input");
        }
    }

    @Test
    void embed_missingDataArray_mapsToModelError() throws Exception {
        try (MockApiServer api = new MockApiServer()) {
            api.enqueue("/embeddings", "{\"object\":\"list\",\"data\":[]}");
            OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                    api.baseUrl(), "test-key", "text-embedding-3-small");

            ModelException ex = assertThrows(ModelException.class, () -> client.embed("x"));
            assertEquals(ModelException.ErrorCode.MODEL_ERROR, ex.getCode());
        }
    }

    @Test
    void embed_trailingSlashBaseUrl_tolerated() throws Exception {
        try (MockApiServer api = new MockApiServer()) {
            api.enqueue("/embeddings", "{\"data\":[{\"index\":0,\"embedding\":[0.5]}]}");
            OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                    api.baseUrl() + "/", "test-key", "text-embedding-3-small", Duration.ofSeconds(5));

            assertArrayEquals(new float[]{0.5f}, client.embed("hello"), 1e-6f);
        }
    }
}
