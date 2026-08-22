package io.github.qwzhang01.agent.channel;

import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.IdentityDecision;
import io.github.qwzhang01.agent.channel.identity.IdentityResolutionException;
import io.github.qwzhang01.agent.channel.identity.IdentityScope;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.memory.InMemoryMemoryStore;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SharedAgentSession} (Stage 12 M12.2).
 * <p>
 * Acceptance mapping (architecture note §10):
 * - "A、B 交替 speak，Agent 能引用对方说过的话" -> multiUser_sharedContext
 * - "@mention 路由、非 mention 只进历史"          -> mentionRouting / history
 * - "记忆来自 channel scope"                      -> channelMemory_isInjected
 */
class SharedAgentSessionTest {

    // ============ Test doubles ============

    /**
     * Recording decorator: delegates to a scripted MockModelClient and
     * captures every request's message list, so tests can assert on what
     * the model actually SAW (shared context, memory injection).
     */
    private static final class RecordingModelClient implements ModelClient {
        private final ModelClient delegate;
        final List<List<ChatMessage>> requests = new ArrayList<>();

        RecordingModelClient(ModelClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(List.copyOf(request.messages()));
            return delegate.chat(request);
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return delegate.stream(request);
        }
    }

    // ============ Fixtures ============

    private static final String CHANNEL = "team-eng";
    private static final String AGENT_ID = "eng-bot";

    private static ServiceAccount account() {
        return ServiceAccount.of("svc-eng-01",
                new AgentIdentity(AGENT_ID, "Engineering Bot", "team-eng-leads"),
                IdentityScope.capabilities("chat"));
    }

    private static SharedAgentSession session(AgentConfig config,
                                              Map<String, Set<String>> roles,
                                              String... members) {
        return new SharedAgentSession(
                new SimpleAgent(config), account(),
                ChannelContext.of(CHANNEL, members),
                (ch, uid) -> roles.getOrDefault(uid, Set.of()),
                null);
    }

    private static AgentConfig config(RecordingModelClient model, ContextBuilder contextBuilder) {
        return new AgentConfig(AGENT_ID, "You are a channel engineering bot.",
                model, null, 10, contextBuilder);
    }

    // ============ Multi-user shared context ============

    @Test
    @DisplayName("A and B alternate speak: one shared state, the model sees both speakers")
    void multiUser_sharedContext() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("调研结论是 X").respondText("继续调研结论 Y"));
        SharedAgentSession session = session(config(model, null),
                Map.of("alice", Set.of("chat"), "bob", Set.of("chat")), "alice", "bob");

        String replyA = session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot 帮我调研 X"));
        String replyB = session.speak(ChannelMessage.mention(CHANNEL, "bob", "继续刚才的调研"));

        assertEquals("调研结论是 X", replyA);
        assertEquals("继续调研结论 Y", replyB);

        // Shared context: both attributed inputs live in ONE state
        AgentState state = session.sharedState();
        List<String> userTexts = state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .map(ChatMessage::content)
                .toList();
        assertEquals(List.of("[from alice] 帮我调研 X", "[from bob] 继续刚才的调研"), userTexts);

        // The second model request saw alice's turn (context continuity)
        assertEquals(2, model.requests.size());
        assertTrue(model.requests.get(1).stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("[from alice]")),
                "B's turn must be able to reference A's earlier turn - shared context proof");
    }

    // ============ Mention routing ============

    @Test
    @DisplayName("plain (non-mention) message: history only, agent not invoked, null returned")
    void mentionRouting_plainMessageNotForwarded() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("hi"));
        SharedAgentSession session = session(config(model, null),
                Map.of("alice", Set.of("chat")), "alice");

        String reply = session.speak(ChannelMessage.of(CHANNEL, "alice", "谁中午吃饭？"));

        assertNull(reply, "no mention, no reply");
        assertTrue(model.requests.isEmpty(), "the agent must not be invoked");
        assertEquals(1, session.history().size(), "but the message IS in channel history");
    }

    @Test
    @DisplayName("mention prefix is stripped and speaker attribution is prefixed")
    void mentionRouting_prefixStrippedAndAttributionAdded() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        SharedAgentSession session = session(config(model, null),
                Map.of("alice", Set.of("chat")), "alice");

        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot: 检查 CI"));

        List<ChatMessage> lastRequest = model.requests.get(0);
        ChatMessage lastUser = lastRequest.get(lastRequest.size() - 1);
        assertEquals("[from alice] 检查 CI", lastUser.content(),
                "mention prefix stripped, speaker attribution added");
    }

    // ============ Fail-closed identity gate ============

    @Test
    @DisplayName("non-member is denied even before history - and never reaches the agent")
    void nonMember_failClosed() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("should never happen"));
        SharedAgentSession session = session(config(model, null),
                Map.of("alice", Set.of("chat")), "alice");

        IdentityResolutionException ex = assertThrows(IdentityResolutionException.class,
                () -> session.speak(ChannelMessage.mention(CHANNEL, "stranger", "@eng-bot hi")));

        assertEquals(IdentityDecision.DenialReason.USER_NOT_IN_CHANNEL, ex.reason());
        assertTrue(model.requests.isEmpty(), "agent must not run");
        assertTrue(session.history().isEmpty(), "denied message must not enter history");
    }

    @Test
    @DisplayName("member without role capabilities: precise EMPTY_PERMISSION_INTERSECTION denial")
    void memberWithoutRole_emptyIntersection() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("nope"));
        SharedAgentSession session = session(config(model, null),
                Map.of("carol", Set.of("calendar.read")), "carol");

        IdentityResolutionException ex = assertThrows(IdentityResolutionException.class,
                () -> session.speak(ChannelMessage.mention(CHANNEL, "carol", "@eng-bot hi")));

        assertEquals(IdentityDecision.DenialReason.EMPTY_PERMISSION_INTERSECTION, ex.reason(),
                "member (not stranger) without matching role gets the precise reason");
    }

    @Test
    @DisplayName("a message from another channel is a wiring bug and fails fast")
    void channelMismatch_failsFast() {
        SharedAgentSession session = session(
                config(new RecordingModelClient(MockModelClient.scripted().respondText("x")), null),
                Map.of("alice", Set.of("chat")), "alice");

        assertThrows(IllegalArgumentException.class,
                () -> session.speak(ChannelMessage.mention("other-channel", "alice", "@eng-bot hi")));
    }

    // ============ History ============

    @Test
    @DisplayName("history records every accepted message in order, mention or not")
    void history_recordsAll() {
        SharedAgentSession session = session(
                config(new RecordingModelClient(MockModelClient.scripted().respondText("ok")), null),
                Map.of("alice", Set.of("chat")), "alice");

        session.speak(ChannelMessage.of(CHANNEL, "alice", "plain talk"));
        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot ping"));

        List<ChannelMessage> history = session.history();
        assertEquals(2, history.size());
        assertEquals("plain talk", history.get(0).text());
        assertTrue(history.get(1).mentionsAgent());
        assertNotNull(history.get(0).timestamp());
    }

    // ============ Channel-scope memory (design D2) ============

    @Test
    @DisplayName("channel-scope memory is injected into the model context")
    void channelMemory_isInjected() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.write(new MemoryEntry(null, "channel:" + CHANNEL, MemoryType.FACT,
                "release-train", "本周冻结发布窗口，周四 20:00 解冻",
                0.9,
                MemoryProvenance.modelDerived("eng-bot", "alice", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null));

        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("收到"));
        SharedAgentSession session = session(
                config(model, SharedAgentSession.channelMemoryContext(store, CHANNEL)),
                Map.of("alice", Set.of("chat")), "alice");

        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot 今天能发版吗"));

        String seen = flatten(model.requests.get(0));
        assertTrue(seen.contains("[Known memories]"), "memory block injected");
        assertTrue(seen.contains("本周冻结发布窗口"),
                "the channel memory content reached the model - D2: recall list, not a new system");
    }

    @Test
    @DisplayName("memories from ANOTHER channel's scope are invisible (isolation)")
    void channelMemory_isolation() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.write(new MemoryEntry(null, "channel:sales", MemoryType.FACT,
                "secret", "Q4 pricing plan: aggressive discount",
                0.9,
                MemoryProvenance.modelDerived("sales-bot", "dave", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null));

        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        SharedAgentSession session = session(
                config(model, SharedAgentSession.channelMemoryContext(store, CHANNEL)),
                Map.of("alice", Set.of("chat")), "alice");

        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot ping"));

        String seen = flatten(model.requests.get(0));
        assertFalse(seen.contains("[Known memories]"), "no memories visible -> no block at all");
        assertFalse(seen.contains("Q4 pricing"), "another channel's memory must not leak");
    }

    // ============ Helpers ============

    private static String flatten(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            sb.append(m.content()).append("\n");
        }
        return sb.toString();
    }
}
