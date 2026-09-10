package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for E3's cache-simulating decorator: LCP accounting, cache
 * domains, usage rewriting, and the honest-zero discipline.
 */
class CacheSimulatingModelClientTest {

    /** Echo client that reports no usage (mock discipline: null usage). */
    static final class NullUsageClient implements ModelClient {
        @Override
        public ModelResponse chat(ModelRequest request) {
            return new ModelResponse("ok", null, "stop", null);
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Done(chat(request)));
        }
    }

    private static ModelRequest request(String model, String... messages) {
        ModelRequest.Builder b = ModelRequest.builder().model(model);
        for (String m : messages) {
            b.addMessage(ChatMessage.user(m));
        }
        return b.build();
    }

    @Test
    @DisplayName("first call in a domain: cold start, zero cached, all tokens written")
    void firstCallIsCold() {
        CacheSimulatingModelClient c = new CacheSimulatingModelClient(new NullUsageClient());
        ModelResponse r = c.chat(request("m", "hello world of tokens"));

        assertEquals(0, r.usage().cachedTokens(), "cold start: nothing cached");
        assertTrue(r.usage().promptTokens() > 0, "rough tokenizer counts the prompt");
        assertEquals(0.0, c.hitRate());
    }

    @Test
    @DisplayName("identical request twice: full LCP hit on the second call")
    void identicalRequestHitsFully() {
        CacheSimulatingModelClient c = new CacheSimulatingModelClient(new NullUsageClient());
        ModelRequest req = request("m", "hello world of tokens", "second message here");
        c.chat(req);
        ModelResponse r = c.chat(req);

        assertEquals(r.usage().promptTokens(), r.usage().cachedTokens(),
                "identical prompt: the whole prompt is a cache hit");
        // cumulative hit rate: first call was cold, second fully hit -> 0.5
        assertEquals(0.5, c.hitRate(), 1e-9);
    }

    @Test
    @DisplayName("append-only history: prefix hits, new tail misses")
    void appendedTailHitsPrefixOnly() {
        CacheSimulatingModelClient c = new CacheSimulatingModelClient(new NullUsageClient());
        c.chat(request("m", "stable prefix message"));
        ModelResponse r = c.chat(request("m", "stable prefix message", "brand new turn"));

        assertTrue(r.usage().cachedTokens() > 0, "the shared prefix hits");
        assertTrue(r.usage().cachedTokens() < r.usage().promptTokens(),
                "the appended tail is fresh input");
        assertTrue(r.usage().promptTokens() > r.usage().cachedTokens());
    }

    @Test
    @DisplayName("history rewrite: prefix destroyed, only the role marker survives")
    void rewrittenPrefixMisses() {
        CacheSimulatingModelClient c = new CacheSimulatingModelClient(new NullUsageClient());
        c.chat(request("m", "turn one content", "turn two content"));
        ModelResponse r = c.chat(request("m", "summary replaced everything", "turn three"));

        // the first USER message's role marker still matches (the only common
        // token) - one 5-char "role:USER" token out of a whole different prompt
        assertTrue(r.usage().cachedTokens() <= 1,
                "a rewritten prefix has at most the role-marker token in common");
        assertTrue(r.usage().cachedTokens() < r.usage().promptTokens());
    }

    @Test
    @DisplayName("different model ids: separate cache domains, no cross-model hit")
    void separateDomainsPerModel() {
        CacheSimulatingModelClient c = new CacheSimulatingModelClient(new NullUsageClient());
        ModelRequest req = request("cheap", "shared prompt text");
        c.chat(req);
        // same TEXT, different model: must NOT hit (different weights/domain)
        ModelResponse r = c.chat(request("premium", "shared prompt text"));

        assertEquals(0, r.usage().cachedTokens(), "different model = different cache domain");
    }

    @Test
    @DisplayName("provider-reported cachedTokens win over the LCP estimate")
    void providerReportedWins() {
        ModelClient reporting = new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                return new ModelResponse("ok", null, "stop",
                        new ModelResponse.TokenUsage(1000, 50, 1050, 900));
            }

            @Override
            public Stream<StreamEvent> stream(ModelRequest request) {
                return Stream.of(new StreamEvent.Done(chat(request)));
            }
        };
        CacheSimulatingModelClient c = new CacheSimulatingModelClient(reporting);
        c.chat(request("m", "some prompt"));
        ModelResponse r = c.chat(request("m", "some prompt", "extra"));

        // provider's own cache report (900) must survive the simulator wrap
        assertTrue(r.usage().cachedTokens() >= 900);
        // and the prompt must not be shrunk below the provider's own count
        assertTrue(r.usage().promptTokens() >= 1000);
    }

    @Test
    @DisplayName("stream path: Done event carries the cache-aware usage")
    void streamDoneCarriesUsage() {
        CacheSimulatingModelClient c = new CacheSimulatingModelClient(new NullUsageClient());
        ModelRequest req = request("m", "streamed prompt");
        c.chat(req);

        List<StreamEvent> events;
        try (Stream<StreamEvent> s = c.stream(request("m", "streamed prompt"))) {
            events = s.toList();
        }
        StreamEvent.Done done = events.stream()
                .filter(e -> e instanceof StreamEvent.Done)
                .map(e -> (StreamEvent.Done) e)
                .findFirst().orElseThrow();

        assertEquals(done.finalResponse().usage().promptTokens(),
                done.finalResponse().usage().cachedTokens(),
                "identical streamed prompt: full hit in the Done usage");
    }

    @Test
    @DisplayName("TokenUsage normalization: negative and oversized cachedTokens clamp")
    void usageNormalization() {
        ModelResponse.TokenUsage negative = new ModelResponse.TokenUsage(100, 10, 110, -5);
        assertEquals(0, negative.cachedTokens(), "negative clamps to 0");

        ModelResponse.TokenUsage oversized = new ModelResponse.TokenUsage(100, 10, 110, 150);
        assertEquals(100, oversized.cachedTokens(), "cached clamps to promptTokens");
    }
}
