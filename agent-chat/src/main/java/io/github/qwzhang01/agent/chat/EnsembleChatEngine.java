package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.RoomMessage;
import io.github.qwzhang01.agent.chat.speaker.BeatPolicy;
import io.github.qwzhang01.agent.core.agent.AgentEvent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Ensemble turn: one user line may produce several persona replies
 * "beats". The first speaker is picked by the wrapped engine's
 * {@code SpeakerPolicy}; after each beat, the {@link BeatPolicy} decides
 * whether another persona speaks next (no immediate self-followup) or the
 * turn stops.
 * <p>
 * Beat detection reads room history (the engine appends each reply before
 * this class regains control), so no fragile event interception is needed:
 * <ul>
 *   <li>{@code historyLength} before the first {@code engine.stream} call;</li>
 *   <li>a new trailing assistant message whose speaker differs from the
 *       previous trailing speaker (or the first reply of the turn) means
 *       the beat succeeded.</li>
 * </ul>
 * <p>
 * Contract with hosts:
 * <ul>
 *   <li>{@link AgentEvent.Done} is emitted exactly once per user turn,
 *       after the last beat; its {@code finalAnswer} is the last beat's
 *       reply. Hosts that need every beat listen to
 *       {@code ChatListener.onReplied}, which fires once per beat.</li>
 *   <li>{@link AgentEvent.BeatStarted} is emitted before each continuation
 *       beat's first delta so hosts can split per-speaker bubbles.</li>
 *   <li>Beat replies land on room history immediately, so the next beat's
 *       context includes what the previous persona said.</li>
 *   <li>The beat input is a stage direction, not a user message: the beat
 *       persona sees the room transcript (including the user line and all
 *       previous replies) plus an instruction like
 *       "(顾时安 speaks next)" without a second user line appended.</li>
 * </ul>
 * <p>
 * Fallback behavior: when the {@code BeatPolicy} misses (empty), throws,
 * or re-picks the last speaker, the turn stops gracefully — the first
 * reply alone is a complete turn.
 */
public final class EnsembleChatEngine {

    /** Default beat cap: first reply + up to 2 continuations. */
    public static final int DEFAULT_MAX_BEATS = 3;

    private final ChatEngine engine;
    private final BeatPolicy beatPolicy;
    private final int maxBeats;
    private final String beatPrompt;

    public EnsembleChatEngine(ChatEngine engine, BeatPolicy beatPolicy) {
        this(engine, beatPolicy, DEFAULT_MAX_BEATS, null);
    }

    public EnsembleChatEngine(ChatEngine engine, BeatPolicy beatPolicy, int maxBeats) {
        this(engine, beatPolicy, maxBeats, null);
    }

    /**
     * @param beatPrompt stage-direction template for continuation beats;
     *                   {@code {name}} is replaced with the beat speaker's
     *                   display name. Null falls back to a default.
     */
    public EnsembleChatEngine(ChatEngine engine, BeatPolicy beatPolicy,
                              int maxBeats, String beatPrompt) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.beatPolicy = Objects.requireNonNull(beatPolicy, "beatPolicy");
        if (maxBeats < 1) {
            throw new IllegalArgumentException("maxBeats must be >= 1");
        }
        this.maxBeats = maxBeats;
        this.beatPrompt = beatPrompt == null || beatPrompt.isBlank()
                ? "(next: {name})"
                : beatPrompt.trim();
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

        // Swallow per-beat Done events; the ensemble emits exactly one Done
        // at the end of the whole turn. Everything else streams through.
        Consumer<AgentEvent> sink = event -> {
            if (!(event instanceof AgentEvent.Done)) {
                ChatEngine.emitHost(listener, event);
            }
        };

        int base = engine.room().history().size();

        // Beat 1: normal speaker pick. The engine appends the user line,
        // picks the speaker, streams, and appends the reply to history.
        engine.stream(userText, sink);

        // The user line alone makes history longer; a real first reply adds
        // a trailing ASSISTANT message. No new assistant line = nobody spoke.
        if (!hasNewAssistantSince(engine.room().history(), base)) {
            return;  // no speaker (or error before append): nothing to continue
        }

        for (int beat = 2; beat <= maxBeats; beat++) {
            RoomMessage last = trailingAssistant(engine.room().history());
            if (last == null) {
                break;
            }
            ChatPersona lastSpeaker = engine.room().member(last.speakerId()).orElse(null);
            if (lastSpeaker == null) {
                break;
            }

            Optional<ChatPersona> next = pickNextBeat(lastSpeaker, last.content());
            if (next.isEmpty()
                    || next.get().personaId().equals(lastSpeaker.personaId())) {
                break;
            }
            ChatPersona beatSpeaker = next.get();

            ChatEngine.emitHost(listener, new AgentEvent.BeatStarted(beatSpeaker.personaId(), beat));
            int before = engine.room().history().size();
            engine.streamForced(beatSpeaker, userText,
                    stageDirection(beatSpeaker), sink);
            if (engine.room().history().size() == before) {
                break;  // beat failed (model error): stop the turn gracefully
            }
        }

        RoomMessage last = trailingAssistant(engine.room().history());
        ChatEngine.emitHost(listener, new AgentEvent.Done(
                last == null ? "" : last.content(), null));
    }

    private Optional<ChatPersona> pickNextBeat(ChatPersona lastSpeaker, String lastReply) {
        try {
            return beatPolicy.nextBeat(engine.room(), lastSpeaker, lastReply);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String stageDirection(ChatPersona beatSpeaker) {
        return beatPrompt.replace("{name}", beatSpeaker.displayName());
    }

    private static RoomMessage trailingAssistant(List<RoomMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            RoomMessage message = history.get(i);
            if (message.role() == io.github.qwzhang01.agent.core.model.ChatRole.ASSISTANT) {
                return message;
            }
        }
        return null;
    }

    /** True when an assistant line exists at index >= base (a reply happened). */
    private static boolean hasNewAssistantSince(List<RoomMessage> history, int base) {
        for (int i = history.size() - 1; i >= base; i--) {
            RoomMessage message = history.get(i);
            if (message.role() == io.github.qwzhang01.agent.core.model.ChatRole.ASSISTANT) {
                return true;
            }
        }
        return false;
    }
}
