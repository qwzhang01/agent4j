package io.github.qwzhang01.agent.channel.ambient;

import io.github.qwzhang01.agent.channel.SharedAgentSession;
import io.github.qwzhang01.agent.channel.collab.VisibilityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The ambient runner: registers standing instructions, wires their
 * triggers, applies the noise gates, and pushes proactive notifications
 * (Stage 12 M12.4, design D3 + D7).
 * <p>
 * Disabled by default (safety default): {@link #register} before
 * {@link #enable()} only RECORDS the instruction - no schedule is armed
 * and no event subscription is active. An ambient agent that starts
 * pushing without an explicit admin opt-in is a bug, not a feature.
 * <p>
 * Trigger pipeline per firing (architecture note §6 T4/T5):
 * <pre>
 * trigger fires -> condition judged
 *   false -> total silence (not even digest)
 *   true  -> NoisePolicy gates
 *     SUPPRESS -> silence          DIGEST -> queue for summary
 *     NOTIFY   -> push now, actor = agent identity (never the event
 *                 originator), NOTIFICATION_SENT lands in the visibility
 *                 stream so the whole channel sees what the agent said
 * </pre>
 * <p>
 * Reuse honesty (design D3, v1 deviation): Stage 7's EventBroker callback
 * is hard-wired to {@code RunManager.resume(runId)} - ambient instructions
 * are not runs, so this engine reuses the MECHANISMS (a
 * {@link ScheduledExecutorService} for schedules; an eventKey registry
 * with subscribe/fire semantics) rather than the RunManager-bound
 * implementations. Unifying onto EventBroker is v2 once it supports
 * non-run listeners.
 */
public class AmbientEngine {

    private static final Logger log = LoggerFactory.getLogger(AmbientEngine.class);

    private final SharedAgentSession session;
    private final NoisePolicy noise;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;

    private final Map<String, AmbientInstruction> instructions = new ConcurrentHashMap<>();
    private final Map<String, List<String>> subscriptionsByEventKey = new ConcurrentHashMap<>();
    private final List<ScheduledFuture<?>> armedSchedules = new CopyOnWriteArrayList<>();
    private final List<Consumer<ProactiveNotification>> sinks = new CopyOnWriteArrayList<>();
    private final List<ProactiveNotification> sent = new CopyOnWriteArrayList<>();

    private volatile boolean enabled = false;

    public AmbientEngine(SharedAgentSession session, NoisePolicy noise) {
        this(session, noise, Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ambient-engine");
            t.setDaemon(true);
            return t;
        }), true);
    }

    /**
     * @param executor      scheduler for SCHEDULED instructions (injectable for tests)
     * @param ownsExecutor  whether shutdown() should close the executor
     */
    public AmbientEngine(SharedAgentSession session, NoisePolicy noise,
                         ScheduledExecutorService executor, boolean ownsExecutor) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.noise = Objects.requireNonNull(noise, "noise policy must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.ownsExecutor = ownsExecutor;
    }

    // ============ Lifecycle ============

    /**
     * Opt in (admin action). Arms all already-registered instructions;
     * instructions registered afterwards arm immediately.
     */
    public synchronized AmbientEngine enable() {
        if (enabled) {
            return this;
        }
        enabled = true;
        for (AmbientInstruction instruction : instructions.values()) {
            arm(instruction);
        }
        log.info("[ambient] Enabled: {} instruction(s) armed", instructions.size());
        return this;
    }

    /**
     * Register a standing instruction. While disabled, this only records
     * it - NO schedule is armed and NO event subscription is active.
     */
    public synchronized AmbientEngine register(AmbientInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction must not be null");
        AmbientInstruction prev = instructions.putIfAbsent(instruction.instructionId(), instruction);
        if (prev != null) {
            throw new IllegalArgumentException(
                    "instructionId already registered: " + instruction.instructionId());
        }
        if (enabled) {
            arm(instruction);
        } else {
            log.info("[ambient] Recorded (engine disabled) '{}': {}", instruction.instructionId(),
                    instruction.description());
        }
        return this;
    }

    /**
     * Stop arming new firings and release the executor (if owned).
     * Already-sent notifications and digest remain readable.
     */
    public synchronized void shutdown() {
        enabled = false;
        for (ScheduledFuture<?> future : armedSchedules) {
            future.cancel(false);
        }
        armedSchedules.clear();
        if (ownsExecutor) {
            executor.shutdownNow();
        }
        log.info("[ambient] Shutdown");
    }

    // ============ Event ingress ============

    /**
     * Fire an external event: all instructions subscribed to the key run
     * their pipeline with the payload. External systems (or a future
     * EventBroker bridge) call this.
     */
    public void fireEvent(String eventKey, Object payload) {
        Objects.requireNonNull(eventKey, "eventKey must not be null");
        if (!enabled) {
            log.debug("[ambient] Fire '{}' ignored: engine disabled", eventKey);
            return;
        }
        List<String> subscribers = subscriptionsByEventKey.get(eventKey);
        if (subscribers == null || subscribers.isEmpty()) {
            log.debug("[ambient] Fire '{}': no subscribers", eventKey);
            return;
        }
        log.info("[ambient] Fire '{}': {} subscriber(s)", eventKey, subscribers.size());
        for (String instructionId : List.copyOf(subscribers)) {
            AmbientInstruction instruction = instructions.get(instructionId);
            if (instruction != null) {
                onTriggered(instruction, payload);
            }
        }
    }

    // ============ The pipeline ============

    /**
     * One firing of one instruction: condition -> noise gates -> push.
     * Package-private: tests drive it directly; production reaches it via
     * schedules and events.
     */
    void onTriggered(AmbientInstruction instruction, Object payload) {
        if (!enabled) {
            return;
        }
        // 1) Condition: not worth saying -> total silence (not even digest)
        boolean worthIt;
        try {
            worthIt = instruction.condition().test(payload);
        } catch (Exception e) {
            log.error("[ambient] Condition of '{}' threw - treated as not-worth-it", 
                    instruction.instructionId(), e);
            return;
        }
        if (!worthIt) {
            log.debug("[ambient] '{}' condition not met - silence", instruction.instructionId());
            return;
        }

        // 2) Noise gates
        Instant now = Instant.now();
        NoisePolicy.Verdict verdict = noise.admit(
                instruction.instructionId(), instruction.importance(), now);
        if (verdict == NoisePolicy.Verdict.SUPPRESS) {
            log.info("[ambient] '{}' suppressed by noise policy", instruction.instructionId());
            return;
        }

        // 3) Produce the notification - actor is the AGENT identity
        String content = instruction.message().apply(payload);
        ProactiveNotification notification = new ProactiveNotification(
                null, instruction.instructionId(), session.channel().channelId(),
                session.identity().agentId(), content, instruction.importance(), now);

        if (verdict == NoisePolicy.Verdict.DIGEST) {
            noise.enqueueDigest(notification);
            log.info("[ambient] '{}' queued into digest", instruction.instructionId());
            return;
        }

        // 4) Realtime push: sinks + visibility stream (whole channel sees it)
        sent.add(notification);
        for (Consumer<ProactiveNotification> sink : sinks) {
            try {
                sink.accept(notification);
            } catch (Exception e) {
                log.error("[ambient] Sink threw on '{}'", instruction.instructionId(), e);
            }
        }
        session.visibility().publish(VisibilityEvent.of(
                session.channel().channelId(),
                VisibilityEvent.Type.NOTIFICATION_SENT,
                null, session.identity().agentId(), null,
                preview(content)));
        log.info("[ambient] '{}' pushed (importance={}): {}",
                instruction.instructionId(), instruction.importance(), preview(content));
    }

    // ============ Wiring ============

    /**
     * Register a push sink (chat bridge, webhook, test collector...).
     */
    public AmbientEngine onNotification(Consumer<ProactiveNotification> sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        sinks.add(sink);
        return this;
    }

    /**
     * Drain the digest queue (assembly layer decides when to summarize).
     */
    public List<ProactiveNotification> drainDigest() {
        return noise.drainDigest();
    }

    /**
     * All realtime notifications sent so far (audit view).
     */
    public List<ProactiveNotification> sent() {
        return List.copyOf(sent);
    }

    /**
     * Registered instruction ids (recorded while disabled, armed when enabled).
     */
    public Set<String> instructions() {
        return Set.copyOf(instructions.keySet());
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ============ Internals ============

    private void arm(AmbientInstruction instruction) {
        // NB: switch pattern matching is preview-only on JDK 17; if-instanceof it is.
        if (instruction.trigger() instanceof AmbientInstruction.Scheduled s) {
            armedSchedules.add(executor.scheduleAtFixedRate(
                    () -> safelyTrigger(instruction, null),
                    s.interval().toMillis(), s.interval().toMillis(), TimeUnit.MILLISECONDS));
        } else if (instruction.trigger() instanceof AmbientInstruction.OnEvent e) {
            subscriptionsByEventKey
                    .computeIfAbsent(e.eventKey(), k -> new CopyOnWriteArrayList<>())
                    .add(instruction.instructionId());
        }
        log.info("[ambient] Armed '{}': {}", instruction.instructionId(), instruction.trigger());
    }

    private void safelyTrigger(AmbientInstruction instruction, Object payload) {
        try {
            onTriggered(instruction, payload);
        } catch (Exception e) {
            log.error("[ambient] Trigger of '{}' failed", instruction.instructionId(), e);
        }
    }

    private static String preview(String text) {
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }
}
