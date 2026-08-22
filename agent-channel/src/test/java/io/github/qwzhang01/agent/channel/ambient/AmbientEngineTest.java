package io.github.qwzhang01.agent.channel.ambient;

import io.github.qwzhang01.agent.channel.ChannelContext;
import io.github.qwzhang01.agent.channel.SharedAgentSession;
import io.github.qwzhang01.agent.channel.collab.VisibilityEvent;
import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.IdentityScope;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@link AmbientEngine} (Stage 12 M12.4).
 * <p>
 * The engine's NoisePolicy uses a degenerate quiet window (00:00-00:00 =
 * never quiet) so verdicts are time-of-day independent: INFO -> DIGEST,
 * WARN -> NOTIFY, always. Quiet-window behavior itself is covered in
 * {@link NoisePolicyTest}.
 */
class AmbientEngineTest {

    private static final String CHANNEL = "team-eng";
    private static final String AGENT_ID = "eng-bot";

    private AmbientEngine engine;

    private SharedAgentSession session() {
        return new SharedAgentSession(
                new SimpleAgent(new AgentConfig(AGENT_ID, "bot", null, null, 10, null)),
                ServiceAccount.of("svc-eng-01",
                        new AgentIdentity(AGENT_ID, "Engineering Bot", "leads"),
                        IdentityScope.capabilities("chat")),
                ChannelContext.of(CHANNEL, "alice", "bob"),
                (ch, uid) -> Set.of("chat"),
                null);
    }

    /** Never-quiet policy so tests are deterministic at any wall time. */
    private static NoisePolicy neverQuiet(int dailyBudget, Duration minInterval) {
        return new NoisePolicy(ZoneId.of("UTC"), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT,
                dailyBudget, minInterval);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    // ============ Disabled by default (safety default) ============

    @Test
    @DisplayName("disabled engine: register records but fireEvent does NOTHING")
    void disabledByDefault_noScheduling() {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(5, Duration.ZERO));
        List<ProactiveNotification> pushed = new ArrayList<>();
        engine.onNotification(pushed::add);

        engine.register(AmbientInstruction.onEvent("i1", "watch PRs", "pr-silent",
                AmbientInstruction.Importance.WARN, p -> true, p -> "PR 沉寂"));

        assertFalse(engine.isEnabled());
        assertEquals(Set.of("i1"), engine.instructions(), "recorded, not armed");

        engine.fireEvent("pr-silent", "pr-77");

        assertTrue(pushed.isEmpty(), "disabled engine never pushes");
        assertTrue(engine.sent().isEmpty());
        assertTrue(engine.drainDigest().isEmpty());
    }

    // ============ Event-triggered pipeline ============

    @Test
    @DisplayName("event trigger: condition true + WARN -> realtime push attributed to the AGENT identity")
    void eventTrigger_pushes() {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(5, Duration.ZERO));
        List<ProactiveNotification> pushed = new ArrayList<>();
        engine.onNotification(pushed::add);

        engine.enable();
        engine.register(AmbientInstruction.onEvent("silence-watch", "跟踪沉默 PR", "pr-silent",
                AmbientInstruction.Importance.WARN,
                p -> p.toString().contains("silent"),         // condition judges the payload
                p -> "PR " + p + " 已沉默 3 天，要不要跟进？"));

        engine.fireEvent("pr-silent", "pr-77-silent");

        assertEquals(1, pushed.size());
        ProactiveNotification n = pushed.get(0);
        assertEquals("silence-watch", n.instructionId());
        assertEquals(CHANNEL, n.channelId());
        assertEquals(AGENT_ID, n.actor(), "attribution is the agent identity, not the event source");
        assertTrue(n.content().contains("pr-77-silent"));
        assertEquals(AmbientInstruction.Importance.WARN, n.importance());
        assertEquals(1, engine.sent().size());
    }

    @Test
    @DisplayName("condition false -> total silence: no push AND no digest entry")
    void conditionFalse_totalSilence() {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(5, Duration.ZERO));
        List<ProactiveNotification> pushed = new ArrayList<>();
        engine.onNotification(pushed::add);

        engine.enable();
        engine.register(AmbientInstruction.onEvent("i1", "d", "k",
                AmbientInstruction.Importance.WARN, p -> false, p -> "never"));

        engine.fireEvent("k", "payload");

        assertTrue(pushed.isEmpty());
        assertTrue(engine.drainDigest().isEmpty(),
                "condition not met means not even the digest hears about it");
    }

    @Test
    @DisplayName("event with no subscribers is a no-op")
    void eventTrigger_noSubscribers() {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(5, Duration.ZERO));
        engine.enable();

        assertDoesNotThrow(() -> engine.fireEvent("nobody-home", null));
    }

    // ============ Noise gates through the engine ============

    @Test
    @DisplayName("INFO instruction: realtime sink silent, digest gets the entry")
    void infoGoesToDigest() {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(5, Duration.ZERO));
        List<ProactiveNotification> pushed = new ArrayList<>();
        engine.onNotification(pushed::add);

        engine.enable();
        engine.register(AmbientInstruction.onEvent("i-info", "每日工单摘要", "tickets",
                AmbientInstruction.Importance.INFO, p -> true, p -> "今日新增工单 " + p));

        engine.fireEvent("tickets", 12);

        assertTrue(pushed.isEmpty(), "INFO never pushes realtime");
        List<ProactiveNotification> digest = engine.drainDigest();
        assertEquals(1, digest.size());
        assertTrue(digest.get(0).content().contains("12"));
    }

    @Test
    @DisplayName("frequency gate through the engine: second firing within the interval is swallowed")
    void frequencyGate_engineLevel() {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(5, Duration.ofHours(1)));  // 1h interval
        List<ProactiveNotification> pushed = new ArrayList<>();
        engine.onNotification(pushed::add);

        engine.enable();
        engine.register(AmbientInstruction.onEvent("flappy", "d", "k",
                AmbientInstruction.Importance.WARN, p -> true, p -> "again"));

        engine.fireEvent("k", 1);
        engine.fireEvent("k", 2);

        assertEquals(1, pushed.size(), "the repeat within one hour is swallowed");
    }

    // ============ Scheduled trigger ============

    @Test
    @DisplayName("scheduled instruction fires periodically once enabled")
    void scheduledTrigger_fires() throws InterruptedException {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(100, Duration.ZERO));
        List<ProactiveNotification> pushed = new ArrayList<>();
        engine.onNotification(pushed::add);

        engine.enable();
        engine.register(AmbientInstruction.scheduled("poller", "每 150ms 检查", Duration.ofMillis(150),
                AmbientInstruction.Importance.WARN, p -> true, p -> "tick"));

        Thread.sleep(600);   // ~4 windows of 150ms

        assertTrue(pushed.size() >= 2, "expected >= 2 firings, got: " + pushed.size());
    }

    // ============ Visibility stream integration ============

    @Test
    @DisplayName("NOTIFICATION_SENT lands in the session visibility stream (whole channel sees it)")
    void notificationSentEvent_published() {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(5, Duration.ZERO));
        List<VisibilityEvent.Type> seen = new ArrayList<>();
        session.subscribe(e -> seen.add(e.type()));

        engine.enable();
        engine.register(AmbientInstruction.onEvent("i1", "d", "k",
                AmbientInstruction.Importance.WARN, p -> true, p -> "say"));
        engine.fireEvent("k", "x");

        assertTrue(seen.contains(VisibilityEvent.Type.NOTIFICATION_SENT));
    }

    // ============ Sink isolation ============

    @Test
    @DisplayName("a throwing sink does not break other sinks")
    void sinkIsolation() {
        SharedAgentSession session = session();
        engine = new AmbientEngine(session, neverQuiet(5, Duration.ZERO));
        List<ProactiveNotification> healthy = new ArrayList<>();
        engine.onNotification(n -> { throw new IllegalStateException("boom"); });
        engine.onNotification(healthy::add);

        engine.enable();
        engine.register(AmbientInstruction.onEvent("i1", "d", "k",
                AmbientInstruction.Importance.WARN, p -> true, p -> "msg"));

        assertDoesNotThrow(() -> engine.fireEvent("k", "x"));
        assertEquals(1, healthy.size(), "the healthy sink still got the push");
    }

    // ============ Registration guards ============

    @Test
    @DisplayName("duplicate instructionId is rejected")
    void duplicateRegistration_rejected() {
        engine = new AmbientEngine(session(), neverQuiet(5, Duration.ZERO));
        engine.register(AmbientInstruction.onEvent("dup", "d", "k",
                AmbientInstruction.Importance.WARN, p -> true, p -> "m"));

        assertThrows(IllegalArgumentException.class,
                () -> engine.register(AmbientInstruction.onEvent("dup", "d2", "k2",
                        AmbientInstruction.Importance.WARN, p -> true, p -> "m")));
    }

    @Test
    @DisplayName("enable() is idempotent; shutdown stops schedules")
    void lifecycle_idempotentEnableAndShutdown() {
        engine = new AmbientEngine(session(), neverQuiet(5, Duration.ZERO));

        engine.enable();
        engine.enable();
        assertTrue(engine.isEnabled());

        engine.shutdown();
        assertFalse(engine.isEnabled());
    }
}
