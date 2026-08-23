package io.github.qwzhang01.agent.trace.reward;

import io.github.qwzhang01.agent.trace.testsupport.TrajectoryFixture;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rule reward mapping + immutable customization + honest no-signal cases
 * (M14.2 verification: default mapping, DONE->+2.0 override, applyTo wither).
 */
class RuleRewardTest {

    @Test
    void defaultsMapTerminalReasons() {
        var reward = RuleReward.defaults();
        assertEquals(1.0, reward.score(TrajectoryFixture.successful("r1")).reward());
        assertEquals(-1.0, reward.score(TrajectoryFixture.failed("r2")).reward());
        assertEquals("rule", reward.score(TrajectoryFixture.successful("r1")).source());
        assertTrue(reward.score(TrajectoryFixture.failed("r2")).explanation().contains("error"));
    }

    @Test
    void withRewardOverrideIsImmutableAndEffective() {
        var base = RuleReward.defaults();
        var customized = base.withReward(io.github.qwzhang01.agent.trace.trajectory.DoneReason.DONE, 2.0);
        assertEquals(2.0, customized.score(TrajectoryFixture.successful("r1")).reward());
        // base untouched
        assertEquals(1.0, base.score(TrajectoryFixture.successful("r1")).reward());
    }

    @Test
    void applyToReturnsNewTrajectoryOriginalUntouched() {
        Trajectory scored = RuleReward.defaults().score(TrajectoryFixture.successful("r1")).applyTo(
                TrajectoryFixture.successful("r1"));
        assertEquals(1.0, scored.reward());
        assertEquals("rule", scored.rewardSource());
        assertNull(TrajectoryFixture.successful("r1").reward());
    }

    @Test
    void emptyStepsScoresZeroWithHonestExplanation() {
        var empty = new Trajectory("t", "r", null,
                io.github.qwzhang01.agent.core.agent.AgentState.Status.ERROR,
                java.util.List.of(), java.util.List.of(), null, null);
        var result = RuleReward.defaults().score(empty);
        assertEquals(0.0, result.reward());
        assertEquals("no steps recorded", result.explanation());
    }

    @Test
    void blankSourceRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RewardResult(1.0, " ", "x"));
    }
}
