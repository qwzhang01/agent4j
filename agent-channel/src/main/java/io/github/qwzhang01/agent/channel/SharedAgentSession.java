package io.github.qwzhang01.agent.channel;

import io.github.qwzhang01.agent.channel.collab.ExecutionVisibility;
import io.github.qwzhang01.agent.channel.collab.TaskBoard;
import io.github.qwzhang01.agent.channel.collab.TaskHandoff;
import io.github.qwzhang01.agent.channel.collab.VisibilityEvent;
import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.ChannelRolePermissions;
import io.github.qwzhang01.agent.channel.identity.IdentityDecision;
import io.github.qwzhang01.agent.channel.identity.IdentityResolver;
import io.github.qwzhang01.agent.channel.identity.ResolvedIdentity;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.memory.MemoryContextBuilder;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryScope;
import io.github.qwzhang01.agent.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A channel-scoped shared agent session (Stage 12 M12.2, design D1).
 * <p>
 * NOT a new Agent type: this is a CONTAINER that wraps any existing
 * {@link Agent} implementation (Mock / OpenAI / Anthropic / orchestrating)
 * - composition over inheritance, the same unification philosophy as
 * Stage 11's AgentWorker. Channel semantics (multi-user routing, shared
 * context, membership gating, history) live entirely in this container
 * and never pollute the Agent interface.
 * <p>
 * Sharing model: ONE shared {@link AgentState} for the whole channel -
 * when A and B alternate {@code speak}, both inputs land in the same
 * conversation, so the agent can reference what the other said. Speaker
 * attribution is encoded as a {@code [from <userId>]} prefix on the
 * forwarded prompt.
 * <p>
 * Identity: every speak passes through {@link IdentityResolver}
 * (Stage 12 M12.1) - fail-closed before the agent runs. Membership is
 * sourced from {@link ChannelContext} (SSOT); role permissions come from
 * the injected provider.
 */
public class SharedAgentSession {

    private static final Logger log = LoggerFactory.getLogger(SharedAgentSession.class);

    private final Agent agent;
    private final ServiceAccount account;
    private final ChannelContext channel;
    private final IdentityResolver resolver;
    private final AgentState sharedState = new AgentState();
    private final List<ChannelMessage> history = new CopyOnWriteArrayList<>();
    private final ExecutionVisibility visibility;
    private final TaskBoard board = new TaskBoard();
    private final List<TaskHandoff> handoffs = new CopyOnWriteArrayList<>();
    /** Optional assembly hook: bind ResolvedIdentity to a PermissionChecker (no security dep). */
    private final Consumer<ResolvedIdentity> identityBinder;
    private volatile ResolvedIdentity lastResolvedIdentity;

    /**
     * @param agent           any Agent implementation (wrapped, never modified)
     * @param account         the service account the agent acts under
     * @param channel         channel metadata (membership SSOT)
     * @param rolePermissions member -> role capabilities (membership is checked first)
     * @param auditSink       optional identity-decision sink; null = no auditing
     */
    public SharedAgentSession(Agent agent,
                              ServiceAccount account,
                              ChannelContext channel,
                              ChannelRolePermissions rolePermissions,
                              Consumer<IdentityDecision> auditSink) {
        this(agent, account, channel, rolePermissions, auditSink, null);
    }

    /**
     * @param identityBinder  optional hook invoked with the resolved identity
     *                        before the agent runs; assembly wires this to a
     *                        PermissionChecker (channel does not depend on security)
     */
    public SharedAgentSession(Agent agent,
                              ServiceAccount account,
                              ChannelContext channel,
                              ChannelRolePermissions rolePermissions,
                              Consumer<IdentityDecision> auditSink,
                              Consumer<ResolvedIdentity> identityBinder) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.account = Objects.requireNonNull(account, "account must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.identityBinder = identityBinder;
        Objects.requireNonNull(rolePermissions, "rolePermissions must not be null");

        // Membership gate + role lookup, combined into the M12.1 contract:
        //   non-member          -> null  (USER_NOT_IN_CHANNEL)
        //   member, no roles    -> empty (EMPTY_PERMISSION_INTERSECTION - more precise)
        ChannelRolePermissions gated = (ch, uid) -> {
            if (!channel.isMember(uid)) {
                return null;
            }
            Set<String> caps = rolePermissions.capabilities(ch, uid);
            return caps != null ? caps : Set.of();
        };
        this.resolver = new IdentityResolver(gated, auditSink).register(account);
        this.visibility = new ExecutionVisibility(channel.channelId());
        this.visibility.subscribe(board);   // D6: the board is a materialized view of the stream

        log.info("[channel] SharedAgentSession up: agent='{}' channel='{}' members={}",
                account.identity().agentId(), channel.channelId(), channel.members());
    }

    // ============ Speaking ============

    /**
     * Speak into the channel.
     * <p>
     * Every message (mention or not) is recorded in the channel history.
     * Only messages that mention the agent are forwarded to it:
     * <ul>
     *   <li>mention -> identity resolution (fail-closed), then
     *       {@code agent.run("[from userId] text", sharedState)}, reply returned</li>
     *   <li>no mention -> returns {@code null}; the agent is not invoked
     *       (humans talking to humans is not the agent's business)</li>
     * </ul>
     *
     * @throws io.github.qwzhang01.agent.channel.identity.IdentityResolutionException
     *         when resolution fails closed (non-member, no permission overlap,
     *         invalid account...) - thrown for BOTH mention and plain messages:
     *         a stranger cannot even talk into the channel through the agent.
     * @implNote synchronized: the shared AgentState is a plain ArrayList
     *         (agent-core contract, unchanged since Stage 1); concurrent
     *         speaks from multiple members would race on it. Serializing
     *         turns ALSO matches channel semantics: one conversation turn
     *         at a time, like a human teammate who does not talk over
     *         people. Finer-grained state locking is v2 (needs agent-core
     *         changes, out of the assembly-stage discipline).
     */
    public synchronized String speak(ChannelMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (!message.channelId().equals(channel.channelId())) {
            throw new IllegalArgumentException(
                    "message belongs to channel '" + message.channelId()
                            + "' but this session serves '" + channel.channelId() + "'");
        }

        // Fail-closed identity gate (M12.1 consumption point)
        ResolvedIdentity identity = resolver.resolve(
                channel.channelId(), message.userId(), account.identity().agentId());
        lastResolvedIdentity = identity;
        if (identityBinder != null) {
            identityBinder.accept(identity);
        }

        history.add(message);

        if (!message.mentionsAgent()) {
            log.debug("[channel] {} (member, no mention - history only): {}",
                    message.userId(), preview(message.text()));
            return null;
        }

        String prompt = "[from " + message.userId() + "] "
                + message.textWithoutMention(account.identity().agentId());
        log.info("[channel] {} -> agent '{}' (actor={}): {}",
                message.userId(), account.identity().agentId(), identity.actor(), preview(prompt));

        String reply = agent.run(prompt, sharedState);
        visibility.publish(VisibilityEvent.of(channel.channelId(),
                VisibilityEvent.Type.AGENT_REPLIED, null,
                account.identity().agentId(), message.userId(), preview(reply)));
        return reply;
    }

    // ============ Views ============

    /**
     * The channel history so far (all messages, mention or not), in order.
     */
    public List<ChannelMessage> history() {
        return List.copyOf(history);
    }

    /**
     * The shared conversation state. Exposed because task handoff (M12.3)
     * and checkpoints legitimately need to inspect/move the shared context.
     */
    public AgentState sharedState() {
        return sharedState;
    }

    /**
     * The channel this session serves.
     */
    public ChannelContext channel() {
        return channel;
    }

    /**
     * The agent identity this session runs under.
     */
    public AgentIdentity identity() {
        return account.identity();
    }

    /**
     * The identity from the most recent successful {@link #speak} (null before the first).
     * Assembly / tests use this plus {@code identityBinder} to constrain tools.
     */
    public ResolvedIdentity lastResolvedIdentity() {
        return lastResolvedIdentity;
    }

    // ============ Tasks & Collaboration (M12.3) ============

    /**
     * Put a task on the board (publishes TASK_STARTED; the board follows).
     *
     * @param description what the task is about
     * @param ownerId     the owning member (must be a channel member)
     * @return the new task id
     */
    public String startTask(String description, String ownerId) {
        Objects.requireNonNull(description, "description must not be null");
        requireMember(ownerId);
        String taskId = UUID.randomUUID().toString();
        visibility.publish(VisibilityEvent.of(channel.channelId(),
                VisibilityEvent.Type.TASK_STARTED, taskId, ownerId, null, description));
        log.info("[channel] Task '{}' started by {} : {}", taskId, ownerId, preview(description));
        return taskId;
    }

    /**
     * Mark a task as waiting for a human decision (publishes WAITING_HUMAN).
     */
    public void waitingHuman(String taskId, String what) {
        requireKnownTask(taskId);
        visibility.publish(VisibilityEvent.of(channel.channelId(),
                VisibilityEvent.Type.WAITING_HUMAN, taskId,
                board.task(taskId).orElseThrow().owner(), what, "waiting: " + what));
    }

    /**
     * Resume a waiting task (publishes RESUMED).
     */
    public void resumeTask(String taskId, String byUser) {
        requireMember(byUser);
        requireKnownTask(taskId);
        visibility.publish(VisibilityEvent.of(channel.channelId(),
                VisibilityEvent.Type.RESUMED, taskId, byUser, byUser, "resumed"));
    }

    /**
     * Complete a task (publishes TASK_COMPLETED).
     */
    public void completeTask(String taskId, String summary) {
        requireKnownTask(taskId);
        visibility.publish(VisibilityEvent.of(channel.channelId(),
                VisibilityEvent.Type.TASK_COMPLETED, taskId,
                board.task(taskId).orElseThrow().owner(), null, summary));
    }

    /**
     * Fail a task (publishes TASK_FAILED).
     */
    public void failTask(String taskId, String reason) {
        requireKnownTask(taskId);
        visibility.publish(VisibilityEvent.of(channel.channelId(),
                VisibilityEvent.Type.TASK_FAILED, taskId,
                board.task(taskId).orElseThrow().owner(), null, reason));
    }

    /**
     * Hand a task from one member to another (Stage 12 D5: the three-part
     * handoff - conversation state continues, working memory is shared via
     * scopes already, board ownership moves).
     * <p>
     * The shared conversation state is NOT rebuilt: a system note about the
     * handoff is appended so the model knows the baton moved, and the next
     * owner's turns land in the same conversation.
     *
     * @param taskId   an existing, non-terminal task
     * @param fromUser must equal the current owner (cannot hand off
     *                 someone else's task)
     * @param toUser   must be a channel member
     * @param note     shown to the model and kept in the audit record
     * @implNote synchronized with {@link #speak}: both mutate the shared
     *         AgentState (the baton note) and must not interleave with a
     *         running conversation turn.
     */
    public synchronized TaskHandoff handoff(String taskId, String fromUser, String toUser, String note) {
        Objects.requireNonNull(fromUser, "fromUser must not be null");
        Objects.requireNonNull(toUser, "toUser must not be null");
        requireMember(fromUser);
        requireMember(toUser);

        io.github.qwzhang01.agent.channel.collab.ChannelTask task = board.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + taskId));
        if (task.isTerminal()) {
            throw new IllegalArgumentException(
                    "Task '" + taskId + "' is terminal (" + task.status() + ") - nothing to hand off");
        }
        if (!task.owner().equals(fromUser)) {
            throw new IllegalArgumentException("Task '" + taskId + "' is owned by '"
                    + task.owner() + "', not '" + fromUser + "' - cannot hand off someone else's task");
        }

        // 1) Conversation continuity: inject the baton-pass note into the shared state
        sharedState.addMessage(io.github.qwzhang01.agent.core.model.ChatMessage.system(
                "[handoff] task " + taskId + " owner " + fromUser + " -> " + toUser
                        + (note != null && !note.isBlank() ? " | note: " + note : "")));

        // 2) Working memory: nothing to move - channel/task scopes are shared by design

        // 3) Board ownership via the event stream (single source of truth)
        visibility.publish(VisibilityEvent.of(channel.channelId(),
                VisibilityEvent.Type.TASK_HANDOFF, taskId, fromUser, toUser, note));

        TaskHandoff record = new TaskHandoff(taskId, fromUser, toUser, note, java.time.Instant.now());
        handoffs.add(record);
        log.info("[channel] Handoff task '{}' : {} -> {} ({})",
                taskId, fromUser, toUser, note);
        return record;
    }

    /**
     * The task board (materialized view of the visibility stream).
     */
    public TaskBoard board() {
        return board;
    }

    /**
     * The execution-visibility stream (subscribe to push updates).
     */
    public ExecutionVisibility visibility() {
        return visibility;
    }

    /**
     * Convenience: subscribe to the visibility stream.
     */
    public SharedAgentSession subscribe(ExecutionVisibility.Listener listener) {
        visibility.subscribe(listener);
        return this;
    }

    /**
     * Handoff audit trail, in order.
     */
    public List<TaskHandoff> handoffs() {
        return List.copyOf(handoffs);
    }

    // ============ Assembly Helpers (design D2) ============

    /**
     * Convenience factory for the channel-scoped memory context: a
     * {@link MemoryContextBuilder} whose recall list is headed by the
     * channel scope (plus optional extra scopes such as "agent:eng-bot").
     * <p>
     * This is Stage 12 D2 in code: shared channel memory is NOT a new
     * memory system - it is the Stage 8 channel scope plugged into the
     * recall list. Governance (PENDING_REVIEW, MemoryAdmin, provenance)
     * comes along for free because it lives in the store, not here.
     *
     * @param store       the shared memory store
     * @param channelId   the channel whose scope is shared
     * @param extraScopes additional scopes visible to this agent (optional)
     */
    public static ContextBuilder channelMemoryContext(MemoryStore store,
                                                      String channelId,
                                                      String... extraScopes) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(channelId, "channelId must not be null");
        List<String> scopes = new ArrayList<>();
        scopes.add(MemoryScope.channel(channelId).value());
        scopes.addAll(List.of(extraScopes));
        return new MemoryContextBuilder(new MemoryRetriever(store), scopes,
                null, null, null, 0);
    }

    // ============ Internals ============

    private void requireMember(String userId) {
        if (!channel.isMember(userId)) {
            throw new IllegalArgumentException("'" + userId + "' is not a member of channel '"
                    + channel.channelId() + "'");
        }
    }

    private void requireKnownTask(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (board.task(taskId).isEmpty()) {
            throw new IllegalArgumentException("Unknown task: " + taskId);
        }
    }

    private static String preview(String text) {
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }
}
