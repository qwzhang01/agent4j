package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CascadeModelClientTest {

    // ============ Test helpers ============

    /** Records what it saw and answers with canned responses / streams. */
    static class RecordingClient implements ModelClient {
        ModelRequest lastRequest;
        int calls;
        ModelResponse nextResponse = ModelResponse.text("recorded");
        List<StreamEvent> nextStream = List.of();

        @Override
        public ModelResponse chat(ModelRequest request) {
            this.lastRequest = request;
            this.calls++;
            return nextResponse;
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            this.lastRequest = request;
            this.calls++;
            return nextStream.stream();
        }
    }

    private static ModelRequest request() {
        return ModelRequest.builder()
                .model("m")
                .addMessage(ChatMessage.user("hi"))
                .build();
    }

    private static ModelResponse usage(ModelResponse r, int prompt, int completion) {
        return new ModelResponse(r.content(), r.toolCalls(), r.finishReason(),
                new ModelResponse.TokenUsage(prompt, completion, prompt + completion));
    }

    // ============ chat: the cascade contract ============

    @Test
    @DisplayName("cheap passes the gate: cheap's answer returned AS-IS, premium never called")
    void cheapPassReturnsAsIs() {
        RecordingClient cheap = new RecordingClient();
        cheap.nextResponse = ModelResponse.text("cheap good answer");
        RecordingClient premium = new RecordingClient();
        CascadeModelClient client = new CascadeModelClient(cheap, premium, new RuleBasedQualityGate());

        ModelResponse out = client.chat(request());

        assertEquals("cheap good answer", out.content());
        assertEquals(1, cheap.calls);
        assertEquals(0, premium.calls, "premium must stay untouched when cheap passes");
    }

    @Test
    @DisplayName("cheap fails the gate: premium re-issues with the ORIGINAL request instance (no contamination)")
    void cheapFailEscalatesClean() {
        RecordingClient cheap = new RecordingClient();
        cheap.nextResponse = ModelResponse.error("broken");  // finish reason error -> gate fails
        RecordingClient premium = new RecordingClient();
        premium.nextResponse = ModelResponse.text("premium rescue");
        CascadeModelClient client = new CascadeModelClient(cheap, premium, new RuleBasedQualityGate());
        ModelRequest req = request();

        ModelResponse out = client.chat(req);

        assertEquals("premium rescue", out.content(), "the caller gets premium's answer");
        assertSame(req, premium.lastRequest,
                "premium receives the ORIGINAL request - no failed-response contamination");
        assertSame(req, cheap.lastRequest, "both tiers saw the same original request");
    }

    @Test
    @DisplayName("escalated answer carries premium's content but MERGED usage (both attempts are real spend)")
    void escalatedUsageMerged() {
        RecordingClient cheap = new RecordingClient();
        cheap.nextResponse = usage(ModelResponse.error("x"), 100, 50);
        RecordingClient premium = new RecordingClient();
        premium.nextResponse = usage(ModelResponse.text("good"), 100, 80);
        CascadeModelClient client = new CascadeModelClient(cheap, premium, new RuleBasedQualityGate());

        ModelResponse out = client.chat(request());

        assertEquals("good", out.content());
        assertNotNull(out.usage());
        assertEquals(200, out.usage().promptTokens(), "prompt tokens of BOTH attempts counted");
        assertEquals(130, out.usage().completionTokens(), "completion tokens of BOTH attempts counted");
        assertEquals(330, out.usage().totalTokens());
    }

    @Test
    @DisplayName("cheap pass: usage passes through untouched (no merge, no rewrite)")
    void cheapPassUsageUntouched() {
        RecordingClient cheap = new RecordingClient();
        ModelResponse cheapAnswer = usage(ModelResponse.text("fine"), 10, 5);
        cheap.nextResponse = cheapAnswer;
        RecordingClient premium = new RecordingClient();
        CascadeModelClient client = new CascadeModelClient(cheap, premium, new RuleBasedQualityGate());

        ModelResponse out = client.chat(request());

        assertEquals(10, out.usage().promptTokens());
        assertSame(cheapAnswer, out, "cheap pass returns the SAME instance, zero rewrite");
    }

    @Test
    @DisplayName("no tier reports usage: escalated answer has null usage (no invented numbers)")
    void noUsageNoInvention() {
        RecordingClient cheap = new RecordingClient();
        cheap.nextResponse = ModelResponse.error("x");
        RecordingClient premium = new RecordingClient();
        premium.nextResponse = ModelResponse.text("answer");
        CascadeModelClient client = new CascadeModelClient(cheap, premium, new RuleBasedQualityGate());

        ModelResponse out = client.chat(request());

        assertNull(out.usage(), "usage is never invented");
        assertEquals("answer", out.content());
    }

    // ============ chat: failure semantics ============

    @Test
    @DisplayName("cheap throwing ModelException propagates - availability is Fallback's job, not quality escalation")
    void cheapCrashPropagates() {
        RecordingClient dead = new RecordingClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                throw new io.github.qwzhang01.agent.core.client.ModelException(
                        io.github.qwzhang01.agent.core.client.ModelException.ErrorCode.MODEL_ERROR, "dead");
            }
        };
        RecordingClient premium = new RecordingClient();
        CascadeModelClient client = new CascadeModelClient(dead, premium, new RuleBasedQualityGate());

        assertThrows(io.github.qwzhang01.agent.core.client.ModelException.class,
                () -> client.chat(request()));
        assertEquals(0, premium.calls, "crash is NOT a quality signal - no silent escalation");
    }

    // ============ stream: buffered replay ============

    @Test
    @DisplayName("stream: cheap passes the gate -> buffered events replayed to the caller")
    void streamCheapPassReplays() {
        RecordingClient cheap = new RecordingClient();
        cheap.nextStream = List.of(
                new StreamEvent.ContentDelta("cheap "),
                new StreamEvent.ContentDelta("answer"),
                new StreamEvent.Done(ModelResponse.text("cheap answer")));
        RecordingClient premium = new RecordingClient();
        CascadeModelClient client = new CascadeModelClient(cheap, premium, new RuleBasedQualityGate());

        List<StreamEvent> out = client.stream(request()).toList();

        assertEquals(3, out.size());
        assertInstanceOf(StreamEvent.Done.class, out.get(2));
        assertEquals("cheap answer", ((StreamEvent.Done) out.get(2)).finalResponse().content());
        assertEquals(0, premium.calls);
    }

    @Test
    @DisplayName("stream: cheap fails the gate -> premium's stream returned, usage merged into Done")
    void streamCheapFailStreamsPremium() {
        RecordingClient cheap = new RecordingClient();
        cheap.nextStream = List.of(
                new StreamEvent.ContentDelta("garbage"),
                new StreamEvent.Done(ModelResponse.error("bad")));  // finish reason error
        RecordingClient premium = new RecordingClient();
        premium.nextStream = List.of(
                new StreamEvent.ContentDelta("premium "),
                new StreamEvent.ContentDelta("rescue"),
                new StreamEvent.Done(usage(ModelResponse.text("premium rescue"), 80, 40)));
        CascadeModelClient client = new CascadeModelClient(cheap, premium, new RuleBasedQualityGate());

        List<StreamEvent> out = client.stream(request()).toList();

        assertEquals(3, out.size());
        StreamEvent.Done done = assertInstanceOf(StreamEvent.Done.class, out.get(2));
        assertEquals("premium rescue", done.finalResponse().content());
        assertEquals(120, done.finalResponse().usage().totalTokens(),
                "merged usage rides the Done event (cheap 0 usage + premium 120)");
        // cheap's error response had NO usage reported -> merged = premium-only numbers
    }

    @Test
    @DisplayName("stream: both tiers fail the gate -> premium's answer returned best-effort with a warn")
    void streamBothFailBestEffort() {
        RecordingClient cheap = new RecordingClient();
        cheap.nextStream = List.of(new StreamEvent.Done(ModelResponse.error("bad")));
        RecordingClient premium = new RecordingClient();
        premium.nextStream = List.of(new StreamEvent.Done(ModelResponse.error("also bad")));
        CascadeModelClient client = new CascadeModelClient(cheap, premium, new RuleBasedQualityGate());

        List<StreamEvent> out = client.stream(request()).toList();

        StreamEvent.Done done = assertInstanceOf(StreamEvent.Done.class, out.get(out.size() - 1));
        assertEquals("error", done.finalResponse().finishReason(),
                "one answer must reach the caller - premium's, best effort");
    }

    // ============ guards ============

    @Test
    @DisplayName("constructor guards: null cheap / premium / gate")
    void constructorGuards() {
        assertThrows(NullPointerException.class,
                () -> new CascadeModelClient(null, new RecordingClient(), new RuleBasedQualityGate()));
        assertThrows(NullPointerException.class,
                () -> new CascadeModelClient(new RecordingClient(), null, new RuleBasedQualityGate()));
        assertThrows(NullPointerException.class,
                () -> new CascadeModelClient(new RecordingClient(), new RecordingClient(), null));
    }

    // ============ composition: availability stays inside the tier ============

    @Test
    @DisplayName("Cascade(Fallback(...)): cheap tier crashes -> fallback INSIDE the tier recovers, gate still judges the survivor")
    void fallbackComposition() {
        io.github.qwzhang01.agent.core.client.ModelClient deadCheap =
                io.github.qwzhang01.agent.model.mock.MockModelClient.scripted();  // empty -> ModelException
        io.github.qwzhang01.agent.model.mock.MockModelClient backup =
                io.github.qwzhang01.agent.model.mock.MockModelClient.scripted().respondText("backup cheap answer");
        io.github.qwzhang01.agent.core.client.FallbackModelClient cheapTier =
                new io.github.qwzhang01.agent.core.client.FallbackModelClient(deadCheap, backup);
        RecordingClient premium = new RecordingClient();
        CascadeModelClient client = new CascadeModelClient(cheapTier, premium, new RuleBasedQualityGate());

        ModelResponse out = client.chat(request());

        assertEquals("backup cheap answer", out.content(),
                "availability handled inside the tier; the gate judged the survivor's answer");
        assertEquals(0, premium.calls);
    }
}
