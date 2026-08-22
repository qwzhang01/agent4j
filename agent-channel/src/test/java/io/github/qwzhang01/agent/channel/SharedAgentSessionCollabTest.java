package io.github.qwzhang01.agent.channel;

import io.github.qwzhang01.agent.channel.collab.ChannelTask;
import io.github.qwzhang01.agent.channel.collab.ExecutionVisibility;
import io.github.qwzhang01.agent.channel.collab.TaskBoard;
import io.github.qwzhang01.agent.channel.collab.VisibilityEvent;
import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.IdentityScope;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.scheduler.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end collab tests for {@link SharedAgentSession} (Stage 12 M12.3).
 * <p>
 * Acceptance mapping (architecture note §10):
 * - "handoff 后 B 的首轮对话能引用 A 阶段的结论"      -> handoff_contextIsContinuous
 * - "TaskBoard owner 变更 / handoff 记录可查"          -> handoff_boardAndAuditTrail
 * - "订阅者按序收到事件；看板与事件流一致（同一事实源）" -> events_inOrder / boardMatchesStream
 */
class SharedAgentSessionCollabTest {

    // ============ Test doubles ============

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

    private static SharedAgentSession session(RecordingModelClient model, String... members) {
        AgentConfig config = new AgentConfig(AGENT_ID, "You are a channel engineering bot.",
                model, null, 10, null);
        return new SharedAgentSession(
                new SimpleAgent(config),
                ServiceAccount.of("svc-eng-01",
                        new AgentIdentity(AGENT_ID, "Engineering Bot", "leads"),
                        IdentityScope.capabilities("chat")),
                ChannelContext.of(CHANNEL, members),
                (ch, uid) -> Set.of("chat"),
                null);
    }

    // ============ Task lifecycle on the board ============

    @Test
    @DisplayName("startTask puts a RUNNING task on the board owned by the member")
    void startTask_boardEntry() {
        SharedAgentSession session = session(
                new RecordingModelClient(MockModelClient.scripted().respondText("ok")), "alice", "bob");

        String taskId = session.startTask("调研 X 库迁移方案", "alice");

        ChannelTask task = session.board().task(taskId).orElseThrow();
        assertEquals("调研 X 库迁移方案", task.description());
        assertEquals("alice", task.owner());
        assertEquals(TaskStatus.RUNNING, task.status());
    }

    @Test
    @DisplayName("startTask refuses a non-member owner")
    void startTask_nonMemberRejected() {
        SharedAgentSession session = session(
                new RecordingModelClient(MockModelClient.scripted().respondText("ok")), "alice");

        assertThrows(IllegalArgumentException.class,
                () -> session.startTask("do something", "stranger"));
    }

    @Test
    @DisplayName("waitingHuman -> resumeTask -> completeTask drive the board through the lifecycle")
    void taskLifecycle_drivesBoard() {
        SharedAgentSession session = session(
                new RecordingModelClient(MockModelClient.scripted().respondText("ok")), "alice");

        String taskId = session.startTask("选型评审", "alice");
        session.waitingHuman(taskId, "选方案 A 还是 B");
        assertEquals(TaskStatus.WAITING_HUMAN, session.board().task(taskId).orElseThrow().status());

        session.resumeTask(taskId, "alice");
        assertEquals(TaskStatus.RUNNING, session.board().task(taskId).orElseThrow().status());

        session.completeTask(taskId, "选了 B");
        assertEquals(TaskStatus.SUCCEEDED, session.board().task(taskId).orElseThrow().status());
    }

    @Test
    @DisplayName("lifecycle calls on unknown tasks fail fast")
    void unknownTask_failsFast() {
        SharedAgentSession session = session(
                new RecordingModelClient(MockModelClient.scripted().respondText("ok")), "alice");

        assertThrows(IllegalArgumentException.class, () -> session.waitingHuman("ghost", "x"));
        assertThrows(IllegalArgumentException.class, () -> session.completeTask("ghost", "y"));
    }

    // ============ Handoff: the three-part handover (design D5) ============

    @Test
    @DisplayName("handoff: board owner moves, audit record kept, baton note injected into state")
    void handoff_boardAndAuditTrail() {
        SharedAgentSession session = session(
                new RecordingModelClient(MockModelClient.scripted().respondText("ok")), "alice", "bob");
        String taskId = session.startTask("迁移方案", "alice");

        var handoff = session.handoff(taskId, "alice", "bob", "初稿在 task 记忆里");

        assertEquals(taskId, handoff.taskId());
        assertEquals("alice", handoff.fromUser());
        assertEquals("bob", handoff.toUser());
        assertEquals("初稿在 task 记忆里", handoff.note());

        assertEquals("bob", session.board().task(taskId).orElseThrow().owner());
        assertEquals(1, session.handoffs().size());

        assertTrue(session.sharedState().getMessages().stream()
                        .anyMatch(m -> m.role() == ChatRole.SYSTEM && m.content() != null
                                && m.content().contains("[handoff]") && m.content().contains("alice -> bob")),
                "a system baton note must be injected into the shared state");
    }

    @Test
    @DisplayName("handoff: B's first turn after the handoff sees A's turn AND the baton note")
    void handoff_contextIsContinuous() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("A 阶段结论：用 gRPC").respondText("继续：gRPC 已验证"));
        SharedAgentSession session = session(model, "alice", "bob");
        String taskId = session.startTask("迁移方案", "alice");

        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot 调研迁移方案"));
        session.handoff(taskId, "alice", "bob", "按 A 的结论继续验证");
        session.speak(ChannelMessage.mention(CHANNEL, "bob", "继续刚才的调研"));

        assertEquals(2, model.requests.size());
        String bSaw = flatten(model.requests.get(1));
        assertTrue(bSaw.contains("[from alice] 调研迁移方案"),
                "B's turn must still see A's earlier turn (shared state, not rebuilt)");
        assertTrue(bSaw.contains("[handoff]"),
                "B's turn must see the baton note - the model knows ownership moved");
        assertTrue(bSaw.contains("[from bob] 继续刚才的调研"));
    }

    @Test
    @DisplayName("handoff guards: unknown task / non-owner / non-member / terminal task")
    void handoff_guards() {
        SharedAgentSession session = session(
                new RecordingModelClient(MockModelClient.scripted().respondText("ok")), "alice", "bob", "carol");
        String taskId = session.startTask("t", "alice");
        String doneTask = session.startTask("done", "alice");
        session.completeTask(doneTask, "finished");

        assertThrows(IllegalArgumentException.class,
                () -> session.handoff("ghost", "alice", "bob", "n"));
        assertThrows(IllegalArgumentException.class,
                () -> session.handoff(taskId, "bob", "carol", "n"),
                "bob does not own the task - cannot hand off someone else's");
        assertThrows(IllegalArgumentException.class,
                () -> session.handoff(taskId, "alice", "stranger", "n"),
                "cannot hand off to a non-member");
        assertThrows(IllegalArgumentException.class,
                () -> session.handoff(doneTask, "alice", "bob", "n"),
                "terminal tasks cannot be handed off");
    }

    // ============ Visibility stream (design D6) ============

    @Test
    @DisplayName("subscribers receive events in order: task + agent replies + handoff")
    void events_inOrder() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("结论").respondText("继续"));
        SharedAgentSession session = session(model, "alice", "bob");
        List<VisibilityEvent.Type> seen = new ArrayList<>();
        session.subscribe(e -> seen.add(e.type()));

        String taskId = session.startTask("调研", "alice");
        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot 调研"));
        session.handoff(taskId, "alice", "bob", "继续");
        session.speak(ChannelMessage.mention(CHANNEL, "bob", "继续"));

        assertEquals(List.of(
                VisibilityEvent.Type.TASK_STARTED,
                VisibilityEvent.Type.AGENT_REPLIED,
                VisibilityEvent.Type.TASK_HANDOFF,
                VisibilityEvent.Type.AGENT_REPLIED), seen,
                "milestones arrive in exact emission order");
    }

    @Test
    @DisplayName("board and external subscribers see the same stream (one source of truth)")
    void boardMatchesStream() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        SharedAgentSession session = session(model, "alice", "bob");
        List<VisibilityEvent> seen = new ArrayList<>();
        session.subscribe(seen::add);

        session.startTask("one", "alice");
        session.startTask("two", "bob");
        session.startTask("three", "alice");

        long startedEvents = seen.stream().filter(e -> e.type() == VisibilityEvent.Type.TASK_STARTED).count();
        assertEquals(3, startedEvents);
        assertEquals(3, session.board().size(),
                "the board is fed by the same events the subscriber saw");
    }

    @Test
    @DisplayName("a throwing subscriber does not break the stream for others (nor the board)")
    void throwingSubscriber_isolated() {
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("ok"));
        SharedAgentSession session = session(model, "alice");
        List<VisibilityEvent.Type> healthy = new ArrayList<>();
        session.subscribe(e -> { throw new IllegalStateException("boom"); });
        session.subscribe(e -> healthy.add(e.type()));

        String taskId = assertDoesNotThrow(() -> session.startTask("t", "alice"));

        assertEquals(List.of(VisibilityEvent.Type.TASK_STARTED), healthy,
                "the healthy subscriber still got the event");
        assertTrue(session.board().task(taskId).isPresent(),
                "the board (also a subscriber) still got the event");
    }

    // ============ Concurrency guard (review finding) ============

    @Test
    @DisplayName("concurrent speaks are serialized: no exception, no lost turn")
    void concurrentSpeaks_serialized() throws InterruptedException {
        var script = MockModelClient.scripted();
        for (int i = 0; i < 8; i++) {
            script.respondText("r" + i);
        }
        SharedAgentSession session = session(
                new RecordingModelClient(script), "alice", "bob");

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                session.speak(ChannelMessage.mention(CHANNEL, idx % 2 == 0 ? "alice" : "bob",
                        "@eng-bot 并发轮 " + idx + "-a"));
                session.speak(ChannelMessage.mention(CHANNEL, idx % 2 == 0 ? "bob" : "alice",
                        "@eng-bot 并发轮 " + idx + "-b"));
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join(5000);
        }

        long userTurns = session.sharedState().getMessages().stream()
                .filter(m -> m.role() == ChatRole.USER).count();
        assertEquals(8, userTurns, "every concurrent turn must land - serialized, none lost");
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
