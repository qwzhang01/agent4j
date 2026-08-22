package io.github.qwzhang01.agent.channel.ambient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Noise control: four gates in order, first failure wins (Stage 12 M12.4,
 * design D7).
 * <p>
 * Design premise: an ambient agent's failure mode is not "doesn't work",
 * it is "too annoying and gets muted by everyone". One bad push can burn
 * the whole channel's trust, so noise control is a first-class citizen,
 * not an afterthought.
 * <p>
 * Gate order and semantics:
 * <ol>
 *   <li><b>Frequency</b> (per instruction, min interval): a repeat within
 *       the interval is SUPPRESSED outright - not even digested.</li>
 *   <li><b>Daily budget</b> (realtime pushes per day): once exhausted,
 *       INFO/WARN pushes are SUPPRESSED; CRITICAL is exempt (a service
 *       down at 3am is worth waking someone). Frequency still applies to
 *       CRITICAL - repeated criticals within the interval are storm-guard.</li>
 *   <li><b>Quiet window</b> (e.g. 22:00-08:00): inside the window INFO
 *       and WARN go to DIGEST; CRITICAL still pushes (the service being
 *       down at 3am is worth waking someone).</li>
 *   <li><b>Importance tiering</b>: outside the window, INFO still goes to
 *       DIGEST by default (summaries, not drips); WARN/CRITICAL push
 *       in realtime.</li>
 * </ol>
 * Digest entries count toward frequency (a digest storm is still a storm)
 * but NOT toward the daily realtime budget.
 * <p>
 * {@link #admit} both decides and records - one atomic call.
 */
public class NoisePolicy {

    /** Outcome of running a notification attempt through the gates. */
    public enum Verdict {
        /** Push in realtime now. */
        NOTIFY,
        /** Queue into the digest for later summarised delivery. */
        DIGEST,
        /** Say nothing at all (frequency-limited or budget-exhausted). */
        SUPPRESS
    }

    private final ZoneId zone;
    private final LocalTime quietFrom;
    private final LocalTime quietTo;
    private final int dailyBudget;
    private final Duration minInterval;

    private final Map<String, Instant> lastEmission = new ConcurrentHashMap<>();
    private final Map<LocalDate, Integer> realtimeCountByDay = new ConcurrentHashMap<>();
    private final List<ProactiveNotification> digestQueue = new CopyOnWriteArrayList<>();

    /**
     * @param zone        timezone for the quiet-window clock
     * @param quietFrom   quiet window start (e.g. 22:00)
     * @param quietTo     quiet window end (e.g. 08:00); window may cross midnight
     * @param dailyBudget max realtime NOTIFY verdicts per day (digest excluded)
     * @param minInterval min interval between emissions of the SAME instruction
     */
    public NoisePolicy(ZoneId zone, LocalTime quietFrom, LocalTime quietTo,
                       int dailyBudget, Duration minInterval) {
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
        this.quietFrom = Objects.requireNonNull(quietFrom, "quietFrom must not be null");
        this.quietTo = Objects.requireNonNull(quietTo, "quietTo must not be null");
        if (dailyBudget < 1) {
            throw new IllegalArgumentException("dailyBudget must be >= 1, got: " + dailyBudget);
        }
        this.dailyBudget = dailyBudget;
        this.minInterval = Objects.requireNonNull(minInterval, "minInterval must not be null");
    }

    /**
     * Default policy: local timezone, 22:00-08:00 quiet, 5 realtime pushes
     * per day, at most one emission per instruction per hour.
     */
    public static NoisePolicy defaults() {
        return new NoisePolicy(ZoneId.systemDefault(), LocalTime.of(22, 0), LocalTime.of(8, 0),
                5, Duration.ofHours(1));
    }

    // ============ The gates ============

    /**
     * Run one attempt through the four gates; records state on
     * NOTIFY/DIGEST verdicts.
     * <p>
     * Order: (1) frequency suppresses everything; (2) tiering decides the
     * INTENT (digest vs realtime); (3) the daily budget gates only
     * realtime intents - a digest is not consumption (except frequency).
     */
    public Verdict admit(String instructionId, AmbientInstruction.Importance importance, Instant now) {
        Objects.requireNonNull(instructionId, "instructionId must not be null");
        Objects.requireNonNull(importance, "importance must not be null");
        Objects.requireNonNull(now, "now must not be null");

        // Gate 1: frequency (suppress outright, digest included)
        Instant last = lastEmission.get(instructionId);
        if (last != null && now.isBefore(last.plus(minInterval))) {
            return Verdict.SUPPRESS;
        }

        boolean critical = importance == AmbientInstruction.Importance.CRITICAL;

        // Gates 3+4 decide the intent: quiet window and importance tiering
        Verdict verdict;
        if (inQuietWindow(now)) {
            verdict = critical ? Verdict.NOTIFY : Verdict.DIGEST;
        } else {
            verdict = importance == AmbientInstruction.Importance.INFO
                    ? Verdict.DIGEST : Verdict.NOTIFY;
        }

        // Gate 2: daily realtime budget - only realtime intents consume it
        if (verdict == Verdict.NOTIFY && !critical && consumedToday(now) >= dailyBudget) {
            return Verdict.SUPPRESS;
        }

        lastEmission.put(instructionId, now);   // digest counts toward frequency too
        if (verdict == Verdict.NOTIFY) {
            realtimeCountByDay.merge(LocalDate.ofInstant(now, zone), 1, Integer::sum);
        }
        return verdict;
    }

    // ============ Digest queue ============

    /**
     * Queue a digest verdict's notification (called by AmbientEngine).
     */
    public void enqueueDigest(ProactiveNotification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        digestQueue.add(notification);
    }

    /**
     * Drain the digest queue: returns everything queued and clears it.
     * The assembly layer delivers digests on its own schedule
     * (e.g. a 09:00 summary task).
     */
    public List<ProactiveNotification> drainDigest() {
        List<ProactiveNotification> drained = List.copyOf(digestQueue);
        digestQueue.clear();
        return drained;
    }

    // ============ Views ============

    /**
     * Whether the given instant falls inside the quiet window.
     */
    public boolean inQuietWindow(Instant now) {
        LocalTime t = LocalTime.ofInstant(now, zone);
        // window crossing midnight: [22:00, 24:00) or [00:00, 08:00)
        if (quietFrom.isAfter(quietTo)) {
            return !t.isBefore(quietFrom) || t.isBefore(quietTo);
        }
        return !t.isBefore(quietFrom) && t.isBefore(quietTo);
    }

    /**
     * Realtime pushes consumed today (for dashboards / tests).
     */
    public int consumedToday(Instant now) {
        return realtimeCountByDay.getOrDefault(LocalDate.ofInstant(now, zone), 0);
    }
}
