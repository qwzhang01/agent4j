package io.github.qwzhang01.agent.channel.ambient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link NoisePolicy} gates (Stage 12 M12.4, D7).
 * All times use UTC so the quiet-window clock is deterministic:
 * window 22:00-08:00, "outside" = 12:00Z, "inside" = 23:00Z.
 */
class NoisePolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant NOON = Instant.parse("2026-08-22T12:00:00Z");
    private static final Instant NIGHT = Instant.parse("2026-08-22T23:00:00Z");

    private static NoisePolicy policy(int dailyBudget, Duration minInterval) {
        return new NoisePolicy(UTC, LocalTime.of(22, 0), LocalTime.of(8, 0),
                dailyBudget, minInterval);
    }

    // ============ Gate 1: frequency ============

    @Test
    @DisplayName("gate 1 frequency: repeat within the interval is SUPPRESSED (even CRITICAL)")
    void gate1_frequency() {
        NoisePolicy policy = policy(5, Duration.ofHours(1));

        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("i1",
                AmbientInstruction.Importance.WARN, NOON));
        assertEquals(NoisePolicy.Verdict.SUPPRESS, policy.admit("i1",
                AmbientInstruction.Importance.WARN, NOON.plusSeconds(1800)),
                "30min later, interval is 1h - swallowed");
        assertEquals(NoisePolicy.Verdict.SUPPRESS, policy.admit("i1",
                AmbientInstruction.Importance.CRITICAL, NOON.plusSeconds(1800)),
                "storm-guard: repeated CRITICALs are also swallowed");
        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("i1",
                AmbientInstruction.Importance.WARN, NOON.plusSeconds(3601)),
                "past the interval it flows again");
    }

    // ============ Gate 2: daily budget ============

    @Test
    @DisplayName("gate 2 budget: realtime pushes beyond the daily cap are SUPPRESSED; CRITICAL exempt")
    void gate2_budget() {
        NoisePolicy policy = policy(2, Duration.ZERO);   // cap = 2/day, no frequency limit

        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("a",
                AmbientInstruction.Importance.WARN, NOON));
        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("b",
                AmbientInstruction.Importance.WARN, NOON.plusSeconds(60)));
        assertEquals(NoisePolicy.Verdict.SUPPRESS, policy.admit("c",
                AmbientInstruction.Importance.WARN, NOON.plusSeconds(120)),
                "third realtime push of the day - budget exhausted");
        assertEquals(NoisePolicy.Verdict.DIGEST, policy.admit("d",
                AmbientInstruction.Importance.INFO, NOON.plusSeconds(180)),
                "digest does not consume budget - INFO still digests");
        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("e",
                AmbientInstruction.Importance.CRITICAL, NOON.plusSeconds(240)),
                "CRITICAL is exempt from the budget");
    }

    @Test
    @DisplayName("gate 2 budget resets on a new day")
    void gate2_budgetResetsNextDay() {
        NoisePolicy policy = policy(1, Duration.ZERO);

        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("a",
                AmbientInstruction.Importance.WARN, NOON));
        assertEquals(NoisePolicy.Verdict.SUPPRESS, policy.admit("b",
                AmbientInstruction.Importance.WARN, NOON.plusSeconds(60)));
        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("c",
                AmbientInstruction.Importance.WARN, NOON.plus(Duration.ofDays(1))),
                "next day: budget is fresh");
    }

    // ============ Gate 3: quiet window ============

    @Test
    @DisplayName("gate 3 quiet window: at 23:00 WARN digests, CRITICAL still pushes; noon WARN pushes")
    void gate3_quietWindow() {
        NoisePolicy policy = policy(5, Duration.ZERO);

        assertEquals(NoisePolicy.Verdict.DIGEST, policy.admit("n1",
                AmbientInstruction.Importance.WARN, NIGHT),
                "quiet window: WARN waits for the digest");
        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("n2",
                AmbientInstruction.Importance.CRITICAL, NIGHT),
                "quiet window does not silence CRITICAL");
        assertEquals(NoisePolicy.Verdict.NOTIFY, policy.admit("n3",
                AmbientInstruction.Importance.WARN, NOON),
                "outside the window: WARN pushes in realtime");
    }

    // ============ Gate 4: importance tiering ============

    @Test
    @DisplayName("gate 4 tiering: INFO always digests, even outside the quiet window")
    void gate4_infoAlwaysDigests() {
        NoisePolicy policy = policy(5, Duration.ZERO);

        assertEquals(NoisePolicy.Verdict.DIGEST, policy.admit("i",
                AmbientInstruction.Importance.INFO, NOON),
                "noon INFO still goes to the digest - summaries, not drips");
    }

    // ============ Quiet-window math ============

    @Test
    @DisplayName("quiet window crosses midnight correctly")
    void quietWindow_midnightCrossing() {
        NoisePolicy policy = policy(5, Duration.ZERO);

        assertTrue(policy.inQuietWindow(Instant.parse("2026-08-22T23:00:00Z")));  // 23:00
        assertTrue(policy.inQuietWindow(Instant.parse("2026-08-23T02:00:00Z")));  // 02:00 next day
        assertTrue(policy.inQuietWindow(Instant.parse("2026-08-22T07:59:59Z")));  // just before 08:00
        assertFalse(policy.inQuietWindow(Instant.parse("2026-08-22T08:00:00Z"))); // boundary out
        assertFalse(policy.inQuietWindow(Instant.parse("2026-08-22T12:00:00Z"))); // noon
        assertFalse(policy.inQuietWindow(Instant.parse("2026-08-22T21:59:59Z"))); // just before 22:00
    }

    // ============ Digest queue ============

    @Test
    @DisplayName("digest queue: enqueue then drain returns everything and clears")
    void digestQueue_drain() {
        NoisePolicy policy = policy(5, Duration.ZERO);

        policy.enqueueDigest(new ProactiveNotification("id1", "i1", "c", "bot", "one",
                AmbientInstruction.Importance.INFO, null));
        policy.enqueueDigest(new ProactiveNotification("id2", "i2", "c", "bot", "two",
                AmbientInstruction.Importance.INFO, null));

        List<ProactiveNotification> drained = policy.drainDigest();
        assertEquals(2, drained.size());
        assertEquals("one", drained.get(0).content());
        assertTrue(policy.drainDigest().isEmpty(), "drained once, empty after");
    }

    // ============ Validation ============

    @Test
    @DisplayName("constructor rejects a zero budget")
    void validation_budget() {
        assertThrows(IllegalArgumentException.class,
                () -> policy(0, Duration.ofHours(1)));
    }
}
