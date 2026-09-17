package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.speaker.BeatPolicy;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.client.ModelClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Ensemble turn: one user line may produce several persona replies
 * ("beats"). The first speaker is picked by the wrapped engine's
 * {@code SpeakerPolicy}; after each beat, the {@link BeatPolicy} decides
 * whether another persona speaks next (no immediate self-followup) or the
 * turn stops.
 * <p>
 * Contract with hosts:
 * <ul>
 *   <li>{@link AgentEvent.Done} is emitted exactly once per user turn,
 *       after the last beat; its {@code finalAnswer} is the last beat's
 *       reply (hosts that need every beat should listen to
 *       {@code ChatListener.onReplied}, which fires per beat).</li>
 *   <li>{@link AgentEvent.ContentDelta}s from each beat stream through
 *       unchanged; hosts that render per-speaker bubbles can split beats
 *       on {@code onReplied} or a beat marker event.</li>
 *   <li>Beat replies are appended to room history immediately, so the
 *       next beat's context includes what the previous persona said.</li>
 * </ul>
 * <p>
 * Fallback behavior: when the {@code BeatPolicy} misses (empty), throws,
 * or re-picks the last speaker only, the turn stops gracefully.
 */
public final class EnsembleChatEngine {

    private final ChatEngine engine;
    private final BeatPolicy beatPolicy;
    private final int maxBeats;

    public EnsembleChatEngine(ChatEngine engine, BeatPolicy beatPolicy, int maxBeats) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.beatPolicy = Objects.requireNonNull(beatPolicy, "beatPolicy");
        if (maxBeats < 1) {
            throw new IllegalArgumentException("maxBeats must be >= 1");
        }
        this.maxBeats = maxBeats;
    }

    public ChatEngine engine() {
        return engine;
    }

    public int maxBeats() {
        return maxBeats;
    }

    /**
     * Blocking helper for tests: run {@link #stream} and return the final
     * (last beat's) answer, or {@code ""} when nobody spoke.
     */
    public String say(String userText) {
        String[] answer = {""};
        stream(userText, event -> {
            if (event instanceof AgentEvent.Done done) {
                answer[0] = done.finalAnswer() == null ? "" : done.finalAnswer();
            }
        });
        return answer[0];
    }

    /**
     * One ensemble turn. Streams every beat's deltas through {@code listener};
     * emits exactly one {@link AgentEvent.Done} after the last beat.
     */
    public void stream(String userText, Consumer<AgentEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        if (userText == null) {
            throw new IllegalArgumentException("userText must not be null");
        }

        BeatCollector collector = new BeatCollector(listener);
        engine.stream(userText, collector);

        // No first speaker -> nothing to continue.
        if (collector.lastSpeaker() == null) {
            return;
        }

        for (int beat = 2; beat <= maxBeats; beat++) {
            ChatPersona lastSpeaker = collector.lastSpeaker();
            String lastReply = collector.lastReply() == null ? "" : collector.lastReply();

            Optional<ChatPersona> next = pickNextBeat(lastSpeaker, lastReply);
            if (next.isEmpty() || next.get().personaId().equals(lastSpeaker.personaId())) {
                break;
            }
            collector.reset();
            engine.streamForced(next.get(), userText, collector);
            if (collector.lastSpeaker() == null) {
                break;
            }
        }

        listener.accept(new AgentEvent.Done(collector.lastReply() == null
                ? "" : collector.lastReply(), null));
    }

    private Optional<ChatPersona> pickNextBeat(ChatPersona lastSpeaker, String lastReply) {
        try {
            return beatPolicy.nextBeat(engine.room(), lastSpeaker, lastReply);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Forwards deltas and captures the per-beat speaker/reply pair from
     * {@code ChatListener.onReplied} (fired once per beat by the engine).
     */
    private static final class BeatCollector implements Consumer<AgentEvent> {

        private final Consumer<AgentEvent> downstream;
        private ChatPersona lastSpeaker;
        private String lastReply;

        BeatCollector(Consumer<AgentEvent> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void accept(AgentEvent event) {
            if (event instanceof AgentEvent.Done) {
                return; // per-beat Done is swallowed; the ensemble emits one at the end
            }
            downstream.accept(event);
        }

        ChatPersona lastSpeaker() {
            return lastSpeaker;
        }

        String lastReply() {
            return lastReply;
        }

        void capture(ChatPersona speaker, String reply) {
            this.lastSpeaker = speaker;
            this.lastReply = reply == null ? "" : reply;
        }

        void reset() {
            this.lastSpeaker = null;
            this.lastReply = null;
        }
    }
}
