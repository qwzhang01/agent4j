package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;

import java.util.Optional;

/**
 * Beat continuation policy for ensemble rooms: after the first reply of a
 * user turn, decide whether another persona should speak next (a "beat".
 * <p>
 * The first speaker is still chosen by the {@link SpeakerPolicy}. This
 * interface governs subsequent beats only — e.g. a director model asked
 * "who would naturally react next, or STOP". Compose as:
 * {@code new EnsembleChatEngine(...).beatPolicy(new DirectorBeatPolicy(...))}.
 * <p>
 * A beat speaker must differ from the last speaker (no immediate
 * self-followup); that rule is enforced by {@code EnsembleChatEngine},
 * not by implementations.
 */
@FunctionalInterface
public interface BeatPolicy {

    /**
     * @param room the ensemble room (history includes the just-finished reply)
     * @param lastSpeaker the persona that just spoke
     * @param lastReply the reply text that just finished
     * @return the next persona to speak, or empty to stop the turn
     */
    Optional<ChatPersona> nextBeat(Room room, ChatPersona lastSpeaker, String lastReply);
}
