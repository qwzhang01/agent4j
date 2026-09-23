package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Side-effect ledger (, harness roadmap).
 * <p>
 * The write-ahead truth for external side effects: a tool/node that has
 * successfully touched the outside world (sent the email, charged the card)
 * records the outcome here BEFORE the run advances. On recovery, the
 * re-executing node consults the ledger first — a hit means the effect
 * already landed, so the recorded result is replayed instead of hitting
 * the external system again. This is the "at-most-once external effect"
 * half of the durability story; the run itself stays at-least-once.
 * <p>
 * Delivery semantics are per-effect and explicit (roadmap: no blanket
 * "idempotent" claims):
 * <ul>
 *   <li>{@link DeliverySemantics#AT_MOST_ONCE} — the effect must never fire
 *       twice; a ledger hit always short-circuits re-execution.</li>
 *   <li>{@link DeliverySemantics#AT_LEAST_ONCE} — duplicates are tolerable
 *       (external system dedupes); ledger hits still replay, but a miss
 *       after an uncertain failure re-executes.</li>
 *   <li>{@link DeliverySemantics#EXACTLY_ONCE} — the honest label for
 *       at-most-once external effect + at-least-once run, i.e. what the
 *       ledger + checkpoint pair actually delivers.</li>
 * </ul>
 * <p>
 * Retry disposition (also explicit, per effect):
 * <ul>
 *   <li>{@link RetryDisposition#RETRYABLE} — infrastructure flake; retry with backoff.</li>
 *   <li>{@link RetryDisposition#NOT_RETRYABLE} — business rejection; no retry.</li>
 *   <li>{@link RetryDisposition#NEEDS_CONFIRMATION} — uncertain outcome
 *       (timeout after send): a human must confirm before anything retries.</li>
 * </ul>
 */
public interface SideEffectLedger {

    /** Explicit delivery semantics for one effect (no blanket "idempotent". */
    enum DeliverySemantics { AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE }

    /** What may happen after an effect fails or lands uncertainly. */
    enum RetryDisposition { RETRYABLE, NOT_RETRYABLE, NEEDS_CONFIRMATION }

    /**
     * One recorded external effect.
     *
     * @param effectId derived idempotent id: runId:nodeId[:callHash]
     * @param runId owning run
     * @param nodeId workflow node that fired the effect
     * @param idempotencyKey business-supplied idempotency key "" = none)
     * @param argsHash SHA-256 prefix of the effect's arguments
     * @param semantics delivery semantics declared for this effect
     * @param disposition retry disposition declared for this effect
     * @param result the outcome the external system returned
     * @param completedAt epoch ms when the effect landed
     */
    record Effect(String effectId, String runId, String nodeId, String idempotencyKey,
                  String argsHash, DeliverySemantics semantics, RetryDisposition disposition,
                  String result, long completedAt) {

        /** Derived idempotent effectId for a node-scoped effect. */
        public static String idFor(String runId, String nodeId) {
            return runId + ":" + nodeId;
        }

        /** Derived idempotent effectId for a call-scoped effect. */
        public static String idFor(String runId, String nodeId, String callHash) {
            return runId + ":" + nodeId + ":" + callHash;
        }
    }

    /**
     * Record a landed effect (write-ahead of run advance). Idempotent by
     * effectId: a duplicate record of the same effect is a no-op returning
     * the original — re-recording after a crash-replay is exactly the
     * expected path.
     */
    Effect record(Effect effect);

    /**
     * Look up whether an effect already landed for this run/node[/call].
     * A hit on resume means: replay the recorded result, do NOT re-call
     * the external system.
     */
    Optional<Effect> lookup(String runId, String nodeId);

    /** Call-scoped lookup (multiple tool calls inside one node). */
    Optional<Effect> lookup(String runId, String nodeId, String callHash);

    /** Effects recorded for a run (diagnostics: "what already landed". */
    List<Effect> effectsForRun(String runId);

    /** All effects (admin / test inspection). */
    List<Effect> allEffects();
}
