package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.channel.ChannelContext;
import io.github.qwzhang01.agent.channel.SharedAgentSession;
import io.github.qwzhang01.agent.channel.ambient.AmbientEngine;
import io.github.qwzhang01.agent.channel.ambient.AmbientInstruction;
import io.github.qwzhang01.agent.channel.ambient.NoisePolicy;
import io.github.qwzhang01.agent.channel.ambient.ProactiveNotification;
import io.github.qwzhang01.agent.channel.collab.VisibilityEvent;
import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.IdentityScope;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Stage 12 acceptance example: ambient mode - the agent that speaks
 * first (architecture note §6, T4 + T5).
 * <p>
 * Demonstrates:
 * <ul>
 *   <li>disabled-by-default safety: fireEvent before enable() does nothing</li>
 *   <li>event-triggered push with condition judgment (WARN, realtime)</li>
 *   <li>frequency gate: a repeat within the interval is swallowed</li>
 *   <li>quiet-window gate: WARN digests, CRITICAL pushes through (D7)</li>
 *   <li>scheduled instruction: periodic checks land in the digest (INFO)</li>
 *   <li>attribution: pushes are the AGENT's, NOTIFICATION_SENT is public</li>
 * </ul>
 * Run:
 * <pre>
 *   mvn install -DskipTests -pl agent-channel -am
 *   mvn compile exec:java -pl examples \
 *     -Dexec.mainClass=io.github.qwzhang01.agent.examples.AmbientExample
 * </pre>
 */
public class AmbientExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Stage 12: Ambient Mode (the agent that speaks first) ===\n");

        SharedAgentSession session = new SharedAgentSession(
                new SimpleAgent(new AgentConfig("eng-bot", "bot", null, null, 10, null)),
                ServiceAccount.of("svc-eng-bot-01",
                        new AgentIdentity("eng-bot", "Engineering Bot", "team-eng-leads"),
                        IdentityScope.capabilities("chat")),
                ChannelContext.of("team-eng", "alice", "bob"),
                (ch, uid) -> Set.of("chat"),
                null);
        session.subscribe(e -> {
            if (e.type() == VisibilityEvent.Type.NOTIFICATION_SENT) {
                System.out.println("    [visibility] NOTIFICATION_SENT actor=" + e.actor()
                        + " | " + e.detail());
            }
        });

        // ===== 1. Disabled by default: nothing is armed =====
        System.out.println("--- 1. disabled by default ---");
        NoisePolicy openPolicy = new NoisePolicy(ZoneId.of("UTC"),
                LocalTime.MIDNIGHT, LocalTime.MIDNIGHT,   // never quiet: deterministic demo
                5, Duration.ofHours(1));
        AmbientEngine engine = new AmbientEngine(session, openPolicy);
        engine.onNotification(n -> System.out.println("    [push] " + n.content()
                + "  (importance=" + n.importance() + ", actor=" + n.actor() + ")"));

        engine.register(AmbientInstruction.onEvent("pr-silence", "跟踪沉默 PR", "pr-silent",
                AmbientInstruction.Importance.WARN,
                p -> ((int) p) >= 3,                       // silent >= 3 days is worth saying
                p -> "PR 已沉默 " + p + " 天，要不要跟进？"));
        engine.fireEvent("pr-silent", 4);                  // recorded, NOT armed
        System.out.println("    fireEvent while disabled -> pushes: " + engine.sent().size());

        // ===== 2. Enable: event trigger with condition judgment =====
        System.out.println("\n--- 2. enable + event trigger (condition judged) ---");
        engine.enable();
        engine.fireEvent("pr-silent", 4);
        System.out.println("    (condition 'silent>=3 days' met -> WARN realtime push above)");

        engine.fireEvent("pr-silent", 1);                  // condition false
        System.out.println("    fireEvent with 1-day silence -> total silence (no push, no digest)");

        // ===== 3. Frequency gate: repeat within 1h is swallowed =====
        System.out.println("\n--- 3. frequency gate ---");
        engine.fireEvent("pr-silent", 5);
        System.out.println("    repeat within the interval -> swallowed, pushes still: "
                + engine.sent().size());

        // ===== 4. Quiet window: WARN digests, CRITICAL breaks through =====
        System.out.println("\n--- 4. quiet window (D7) ---");
        LocalTime now = LocalTime.now(ZoneId.of("UTC"));
        NoisePolicy nightPolicy = new NoisePolicy(ZoneId.of("UTC"),
                now.minusMinutes(30), now.plusMinutes(30), 5, Duration.ZERO);
        AmbientEngine nightEngine = new AmbientEngine(session, nightPolicy);
        nightEngine.enable();
        nightEngine.onNotification(n -> System.out.println("    [push] " + n.content()
                + "  (importance=" + n.importance() + ")"));

        nightEngine.register(AmbientInstruction.onEvent("ci-broken", "CI 挂了喊人", "ci-failed",
                AmbientInstruction.Importance.CRITICAL, p -> true,
                p -> "CI 红了：main 分支构建失败，需要立即处理"));
        nightEngine.register(AmbientInstruction.onEvent("pr-review", "提醒 review", "pr-opened",
                AmbientInstruction.Importance.WARN, p -> true,
                p -> "有新 PR 等待 review"));

        nightEngine.fireEvent("ci-failed", "main");
        System.out.println("    ^ CRITICAL breaks through the quiet window (3am service down)");
        nightEngine.fireEvent("pr-opened", "pr-42");
        System.out.println("    WARN inside the window -> queued to digest, not pushed");

        runTail(engine, nightEngine, session);
    }

    // ===== 5. Scheduled instruction + digest + wrap-up (split for readability) =====
    private static void runTail(AmbientEngine engine, AmbientEngine nightEngine,
                                SharedAgentSession session) throws InterruptedException {
        System.out.println("\n--- 5. scheduled instruction (INFO -> digest) ---");
        engine.register(AmbientInstruction.scheduled("ticket-digest", "工单周期巡检",
                Duration.ofMillis(300),
                AmbientInstruction.Importance.INFO, p -> true,
                p -> "巡检：待处理工单 " + (2 + (int) (Math.random() * 3)) + " 条"));
        Thread.sleep(1100);   // ~3 firings of 300ms

        List<ProactiveNotification> digest = engine.drainDigest();
        System.out.println("    digest entries after ~1s of polling: " + digest.size()
                + " (INFO never pushes realtime)");
        digest.forEach(n -> System.out.println("    [digest] " + n.content()));

        System.out.println("\n--- 6. wrap-up ---");
        System.out.println("    realtime pushes sent: " + engine.sent().size()
                + " + night engine: " + nightEngine.sent().size());
        System.out.println("    attribution: every push actor = " + session.identity().agentId()
                + " (the agent's identity, never the event source)");
        engine.shutdown();
        nightEngine.shutdown();
        System.out.println("\n=== Stage 12 acceptance: ambient mode OK ===");
    }
}
