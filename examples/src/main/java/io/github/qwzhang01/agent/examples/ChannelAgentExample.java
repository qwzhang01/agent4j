package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.channel.ChannelContext;
import io.github.qwzhang01.agent.channel.ChannelMessage;
import io.github.qwzhang01.agent.channel.SharedAgentSession;
import io.github.qwzhang01.agent.channel.collab.ChannelTask;
import io.github.qwzhang01.agent.channel.collab.VisibilityEvent;
import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.IdentityResolutionException;
import io.github.qwzhang01.agent.channel.identity.IdentityScope;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.scheduler.TaskStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Stage 12 acceptance example: a channel-scoped shared agent.
 * <p>
 * The scenario (architecture note §6, T0-T3 + T5): eng-bot is deployed
 * into #team-eng as a TEAM member - three members share ONE agent with
 * ONE conversation, tasks can be handed off without losing context, and
 * identity is fail-closed.
 * <pre>
 *   T0  deploy: ServiceAccount eng-bot (granted [chat]) + channel members
 *   T1  alice @eng-bot starts a task (identity gate + channel memory)
 *   T2  alice hands the task off to bob (state/memory/board, three parts)
 *   T3  the board and the visibility stream show progress to everyone
 *   T5  identity denials: member-without-role vs stranger (fail-closed)
 * </pre>
 * Run:
 * <pre>
 *   mvn install -DskipTests -pl agent-channel -am
 *   mvn compile exec:java -pl examples \
 *     -Dexec.mainClass=io.github.qwzhang01.agent.examples.ChannelAgentExample
 * </pre>
 */
public class ChannelAgentExample {

    public static void main(String[] args) {
        System.out.println("=== Stage 12: Channel-Scoped Shared Agent (multiplayer) ===\n");

        // ===== T0. Deploy: service identity + channel + roles =====
        ServiceAccount account = ServiceAccount.of("svc-eng-bot-01",
                new AgentIdentity("eng-bot", "Engineering Bot", "team-eng-leads"),
                new IdentityScope(Set.of("chat"),
                        Set.of("channel:team-eng", "agent:eng-bot"),   // memory namespaces
                        Set.of("internal")));

        // alice/bob hold 'chat' in this channel; carol is a member with NO matching role
        Map<String, Set<String>> roles = Map.of(
                "alice", Set.of("chat"),
                "bob", Set.of("chat"),
                "carol", Set.of("calendar.read"));

        // Channel-shared memory: the whole channel sees this release-freeze fact
        InMemoryMemoryStore memory = new InMemoryMemoryStore();
        memory.write(new MemoryEntry(null, "channel:team-eng", MemoryType.FACT,
                "release-train", "本周发布窗口冻结，周四 20:00 解冻", 0.9,
                MemoryProvenance.modelDerived("eng-bot", "alice", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null));

        // The wrapped agent: ANY existing Agent implementation, zero changes (D1)
        var agent = new SimpleAgent(new AgentConfig("eng-bot",
                "You are the shared engineering bot of #team-eng.",
                MockModelClient.scripted()
                        .respondText("收到。注意到频道记忆：本周发布窗口冻结（周四 20:00 解冻），"
                                + "迁移方案我会按这个约束排期。")
                        .respondText("接续 alice 的调研：约束仍是周四解冻，我建议先把只读部分做完。"),
                null, 10,
                SharedAgentSession.channelMemoryContext(memory, "team-eng", "agent:eng-bot")));

        SharedAgentSession session = new SharedAgentSession(agent, account,
                ChannelContext.of("team-eng", "alice", "bob", "carol"),
                (ch, uid) -> roles.getOrDefault(uid, Set.of()),
                null);

        // Everyone (humans/frontends) subscribes to the same visibility stream
        session.subscribe(e -> System.out.println("    [visibility] " + e.type()
                + " task=" + shortId(e.taskId()) + " actor=" + e.actor()
                + (e.detail() != null ? " | " + e.detail() : "")));

        // ===== T1. alice starts a task and mentions the agent =====
        System.out.println("--- T1: alice starts a task ---");
        String taskId = session.startTask("X 库迁移方案", "alice");
        String reply = session.speak(ChannelMessage.autoDetect(
                "team-eng", "alice", "@eng-bot 帮我调研 X 库的迁移方案", "eng-bot"));
        System.out.println("    eng-bot -> " + reply + "\n");

        session.waitingHuman(taskId, "选保守方案还是激进方案");

        // ===== T2. alice goes offline, bob takes over (three-part handoff) =====
        System.out.println("--- T2: handoff alice -> bob ---");
        session.handoff(taskId, "alice", "bob", "迁移约束在频道记忆里，按保守方案继续");
        String bobReply = session.speak(ChannelMessage.mention(
                "team-eng", "bob", "继续刚才的迁移调研"));
        System.out.println("    eng-bot -> " + bobReply + "\n");
        System.out.println("    (bob's turn ran in the SAME conversation: the model saw "
                + "alice's turn + the [handoff] note)\n");

        // ===== T3. The board is visible to every member =====
        System.out.println("--- T3: task board (visible to all) ---");
        for (ChannelTask task : session.board().tasks()) {
            System.out.println("    " + shortId(task.taskId()) + " [" + task.status() + "] owner="
                    + task.owner() + " - " + task.description());
        }
        System.out.println("    handoff audit: " + session.handoffs().size() + " record(s): "
                + session.handoffs().get(0).fromUser() + " -> " + session.handoffs().get(0).toUser());

        session.completeTask(taskId, "按保守方案完成，周四解冻后执行");

        // ===== T5. Identity is fail-closed (D4) =====
        System.out.println("\n--- T5: identity denials (fail-closed) ---");
        try {
            session.speak(ChannelMessage.mention("team-eng", "carol", "@eng-bot 帮我查日历"));
        } catch (IdentityResolutionException e) {
            System.out.println("    carol (member, no matching role) -> DENIED ["
                    + e.reason() + "]");
        }
        try {
            session.speak(ChannelMessage.mention("team-eng", "stranger", "@eng-bot hi"));
        } catch (IdentityResolutionException e) {
            System.out.println("    stranger (not a member)          -> DENIED ["
                    + e.reason() + "]");
        }

        System.out.println("\n--- final board ---");
        session.board().tasks().forEach(t -> System.out.println("    " + shortId(t.taskId())
                + " [" + t.status() + "] owner=" + t.owner()));
        System.out.println("\nwaiting tasks: " + session.board().byStatus(TaskStatus.WAITING_HUMAN).size());

        System.out.println("\n=== Stage 12 acceptance: shared agent OK ===");
    }

    private static String shortId(String id) {
        return id == null ? "-" : id.substring(0, Math.min(8, id.length()));
    }
}
