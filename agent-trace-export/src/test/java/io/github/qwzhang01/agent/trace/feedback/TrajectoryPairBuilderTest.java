package io.github.qwzhang01.agent.trace.feedback;

import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.trace.testsupport.TrajectoryFixture;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Same-prompt pairing semantics (M14.4 verification): prompt prefix ends at
 * the first USER message, suffix splitting, mismatched prompts fail fast.
 */
class TrajectoryPairBuilderTest {

    @Test
    void promptPrefixEndsAtFirstUserMessage() {
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        var prefix = TrajectoryPairBuilder.promptPrefix(good);
        assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER),
                prefix.stream().map(m -> m.role()).toList());
        assertEquals("查订单 8842", prefix.get(1).content());
        // everything after the prompt is the rollout's response
        var suffix = TrajectoryPairBuilder.responseSuffix(good);
        assertEquals(List.of(ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT),
                suffix.stream().map(m -> m.role()).toList());
    }

    @Test
    void samePromptRolloutsPair() {
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        Trajectory bad = TrajectoryFixture.badRollout("r2");
        assertDoesNotThrow(() -> TrajectoryPairBuilder.requireSharedPrompt(good, bad));
        var pair = TrajectoryPairBuilder.pair(good, bad, "A", "tester");
        assertEquals("traj-good", pair.trajectoryA());
        assertEquals("traj-bad", pair.trajectoryB());
        assertEquals("A", pair.preferred());
        assertTrue(pair.pairId().startsWith("pair-"));
    }

    @Test
    void differentPromptsRejected() {
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        Trajectory otherTask = TrajectoryFixture.successful("r2"); // user says "hello"
        var error = assertThrows(IllegalArgumentException.class,
                () -> TrajectoryPairBuilder.requireSharedPrompt(good, otherTask));
        assertTrue(error.getMessage().contains("SAME prompt"));
    }

    @Test
    void pairRecordRejectsNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> new io.github.qwzhang01.agent.trace.feedback.PreferencePair(
                        null, "t1", "t1", "A", "x", null));  // same id twice
        assertThrows(IllegalArgumentException.class,
                () -> new io.github.qwzhang01.agent.trace.feedback.PreferencePair(
                        null, "t1", "t2", "C", "x", null));  // invalid side
    }
}
