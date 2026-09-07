package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.retry.RetryPolicy;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RetryPolicy} and the retry loop inside
 * {@link io.github.qwzhang01.agent.chat.ChatEngine}.
 */
class RetryPolicyTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");

    // ============ RetryPolicy.never() contract ============

    @Test
    void neverPolicy_neverRetries() {
        RetryPolicy policy = RetryPolicy.never();
        assertFalse(policy.shouldRetry("any reply", 0));
        assertFalse(policy.shouldRetry("", 0));
        assertEquals(0, policy.maxAttempts());
        assertNull(policy.retryExtraText());
    }

    @Test
    void withoutRetryPolicy_behaviorUnchanged() {
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(MockModelClient.scripted().respondText("normal reply"))
                .build();

        List<AgentEvent> events = new ArrayList<>();
        room.stream("hi", events::add);

        // Sequence: ContentDelta → TurnTrace → Done (same as before A7)
        assertEquals(3, events.size());
        assertInstanceOf(AgentEvent.ContentDelta.class, events.get(0));
        assertInstanceOf(AgentEvent.TurnTrace.class, events.get(1));
        AgentEvent.Done done = assertInstanceOf(AgentEvent.Done.class, events.get(2));
        assertEquals("normal reply", done.finalAnswer());
    }

    // ============ Retry triggered — second attempt accepted ============

    @Test
    void policy_hit_triggersSecondGeneration() {
        // First response is "bad", second is "good".
        MockModelClient model = MockModelClient.scripted()
                .respondText("bad reply")
                .respondText("good reply");

        RetryPolicy policy = new RetryPolicy() {
            @Override
            public boolean shouldRetry(String reply, int retriesDone) {
                return reply.contains("bad");
            }

            @Override
            public int maxAttempts() { return 1; }

            @Override
            public String retryExtraText() { return "IMPORTANT: do not say bad things."; }
        };

        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(model)
                .retryPolicy(policy)
                .build();

        List<AgentEvent> events = new ArrayList<>();
        room.stream("hi", events::add);

        // Both attempts stream ContentDelta; only one TurnTrace and one Done.
        long deltas = events.stream().filter(e -> e instanceof AgentEvent.ContentDelta).count();
        assertEquals(2, deltas, "ContentDelta from both attempts must be forwarded");

        long traces = events.stream().filter(e -> e instanceof AgentEvent.TurnTrace).count();
        assertEquals(1, traces, "exactly one TurnTrace must be emitted (for the final result)");

        AgentEvent.Done done = (AgentEvent.Done) events.stream()
                .filter(e -> e instanceof AgentEvent.Done)
                .findFirst().orElseThrow();
        assertEquals("good reply", done.finalAnswer(),
                "Done must carry the retry (accepted) reply");

        // TurnTrace is just before Done.
        int traceIdx = indexOfFirst(events, AgentEvent.TurnTrace.class);
        int doneIdx = indexOfFirst(events, AgentEvent.Done.class);
        assertTrue(traceIdx < doneIdx, "TurnTrace must precede Done");
    }

    @Test
    void retryExtraText_isInjectedForRetryAttempt() {
        AtomicInteger callCount = new AtomicInteger();
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("bad").respondText("ok"));

        RetryPolicy policy = new RetryPolicy() {
            @Override
            public boolean shouldRetry(String reply, int retriesDone) {
                return reply.contains("bad");
            }

            @Override
            public int maxAttempts() { return 1; }

            @Override
            public String retryExtraText() { return "Never say bad."; }
        };

        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(model)
                .retryPolicy(policy)
                .build();

        room.say("hi");

        assertEquals(2, model.requests.size(), "two model calls expected (initial + 1 retry)");
        // First attempt: no extra text injected.
        boolean firstHasExtra = model.requests.get(0).messages().stream()
                .anyMatch(m -> "Never say bad.".equals(m.content()));
        assertFalse(firstHasExtra, "first attempt must NOT contain retryExtraText");
        // Second attempt: retryExtraText injected as system message.
        boolean secondHasExtra = model.requests.get(1).messages().stream()
                .anyMatch(m -> "Never say bad.".equals(m.content()));
        assertTrue(secondHasExtra, "retry attempt must contain retryExtraText");
    }

    // ============ maxAttempts cap — no infinite loop ============

    @Test
    void maxAttempts_cap_preventsInfiniteLoop() {
        // Policy always triggers but maxAttempts = 1 → only 2 total runs.
        MockModelClient model = MockModelClient.scripted()
                .respondText("still bad")
                .respondText("still bad 2");

        RetryPolicy alwaysRetry = new RetryPolicy() {
            @Override
            public boolean shouldRetry(String reply, int retriesDone) { return true; }

            @Override
            public int maxAttempts() { return 1; }

            @Override
            public String retryExtraText() { return "try harder"; }
        };

        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(model)
                .retryPolicy(alwaysRetry)
                .build();

        // Must complete without hanging; final answer is from the capped retry.
        String result = room.say("hi");
        assertEquals("still bad 2", result, "after cap, accept whatever the last attempt returned");
    }

    @Test
    void maxAttempts_zero_sameAsNever() {
        MockModelClient model = MockModelClient.scripted().respondText("only once");

        RetryPolicy zeroMax = new RetryPolicy() {
            @Override
            public boolean shouldRetry(String reply, int retriesDone) { return true; }

            @Override
            public int maxAttempts() { return 0; }

            @Override
            public String retryExtraText() { return "extra"; }
        };

        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(model)
                .retryPolicy(zeroMax)
                .build();

        // maxAttempts = 0 means no retries allowed; accept after initial run.
        assertEquals("only once", room.say("hi"));
    }

    // ============ room history consistency ============

    @Test
    void finalReply_writtenToRoomHistory_notRetryAttempts() {
        MockModelClient model = MockModelClient.scripted()
                .respondText("bad")
                .respondText("accepted");

        RetryPolicy policy = new RetryPolicy() {
            @Override public boolean shouldRetry(String reply, int r) { return reply.contains("bad"); }
            @Override public int maxAttempts() { return 1; }
            @Override public String retryExtraText() { return null; }
        };

        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(model)
                .retryPolicy(policy)
                .build();

        room.say("hello");

        // Room history: user message + accepted reply only (not the bad reply).
        assertEquals(2, room.room().history().size());
        assertEquals("hello", room.room().history().get(0).content());
        assertEquals("accepted", room.room().history().get(1).content());
    }

    // ============ Helpers ============

    private static <T extends AgentEvent> int indexOfFirst(List<AgentEvent> events, Class<T> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) return i;
        }
        return -1;
    }

    private static <T> T assertInstanceOf(Class<T> type, Object obj) {
        assertTrue(type.isInstance(obj),
                "Expected " + type.getSimpleName() + " but got " + obj.getClass().getSimpleName());
        return type.cast(obj);
    }
}
