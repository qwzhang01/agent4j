package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.context.ContextAssembler;
import io.github.qwzhang01.agent.chat.guard.ConsistencyGuard;
import io.github.qwzhang01.agent.chat.guard.ConsistencyVerdict;
import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.model.RoomMessage;
import io.github.qwzhang01.agent.chat.speaker.SpeakerPolicy;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One room turn: pick a speaker, assemble context, stream, then notify
 * the host. Does not persist, score relationships, or rewrite persona.
 * Optional {@link ConsistencyGuard} may warn after Done; default is no-op.
 */
public final class ChatEngine {

    public static final int DEFAULT_MAX_STEPS = 1;

    private static final Logger log = LoggerFactory.getLogger(ChatEngine.class);

    private final Room room;
    private final SpeakerPolicy speakerPolicy;
    private final ContextAssembler assembler;
    private final ModelClient modelClient;
    private final int maxSteps;
    private final ToolRegistry tools;
    private final List<ChatListener> listeners;
    private final ConsistencyGuard consistencyGuard;

    ChatEngine(Room room, SpeakerPolicy speakerPolicy, ContextAssembler assembler,
               ModelClient modelClient, int maxSteps, ToolRegistry tools,
               List<ChatListener> listeners, ConsistencyGuard consistencyGuard) {
        this.room = Objects.requireNonNull(room, "room");
        this.speakerPolicy = Objects.requireNonNull(speakerPolicy, "speakerPolicy");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be >= 1");
        }
        this.maxSteps = maxSteps;
        this.tools = tools;
        this.listeners = List.copyOf(listeners);
        this.consistencyGuard = consistencyGuard == null
                ? ConsistencyGuard.noop()
                : consistencyGuard;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Room room() {
        return room;
    }

    /**
     * Blocking helper for tests: run {@link #stream} and return the final
     * answer, or {@code ""} when nobody spoke.
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
     * Append the user line, pick a speaker, stream, then write the reply.
     * An empty pick calls {@link ChatListener#onNoSpeaker} and emits no events.
     */
    public void stream(String userText, Consumer<AgentEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        if (userText == null) {
            throw new IllegalArgumentException("userText must not be null");
        }

        room.append(RoomMessage.user(userText));

        Optional<ChatPersona> picked = speakerPolicy.pick(room, userText);
        if (picked.isEmpty()) {
            fireNoSpeaker(userText);
            return;
        }
        ChatPersona speaker = picked.get();

        List<ChatMessage> prefix = assembler.assemble(room, speaker, userText);
        AgentState state = new AgentState();
        prefix.forEach(state::addMessage);

        // systemPrompt stays null: PersonaSource already injected it.
        AgentConfig config = new AgentConfig(
                speaker.personaId(), null, modelClient, tools, maxSteps);
        SimpleAgent agent = new SimpleAgent(config);

        try {
            agent.stream(ChatMessage.user(userText), state, event -> {
                if (event instanceof AgentEvent.Done done) {
                    String reply = done.finalAnswer() == null ? "" : done.finalAnswer();
                    room.append(RoomMessage.assistant(speaker.personaId(), reply));
                    checkConsistency(speaker, userText, reply);
                    fireReplied(speaker, userText, reply);
                } else if (event instanceof AgentEvent.Error err) {
                    fireError(speaker, userText, err.message(), err.cause());
                }
                listener.accept(event);
            });
        } catch (RuntimeException e) {
            log.error("chat stream failed in room '{}': {}", room.roomId(), e.getMessage());
            fireError(speaker, userText, e.getMessage(), e);
            listener.accept(new AgentEvent.Error(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e));
        }
    }

    private void checkConsistency(ChatPersona speaker, String userText, String reply) {
        ConsistencyVerdict verdict;
        try {
            verdict = consistencyGuard.check(room, speaker, userText, reply);
        } catch (RuntimeException e) {
            log.warn("ConsistencyGuard.check failed: {}", e.getMessage());
            return;
        }
        if (verdict == null || verdict.consistent()) {
            return;
        }
        log.warn("consistency warning in room '{}': {}", room.roomId(), verdict.warning());
        fireConsistencyWarning(speaker, userText, reply, verdict.warning());
    }

    private void fireConsistencyWarning(ChatPersona speaker, String userText, String reply,
                                        String warning) {
        for (ChatListener listener : listeners) {
            try {
                listener.onConsistencyWarning(room, speaker, userText, reply, warning);
            } catch (RuntimeException e) {
                log.warn("ChatListener.onConsistencyWarning failed: {}", e.getMessage());
            }
        }
    }

    private void fireReplied(ChatPersona speaker, String userText, String reply) {
        for (ChatListener listener : listeners) {
            try {
                listener.onReplied(room, speaker, userText, reply);
            } catch (RuntimeException e) {
                log.warn("ChatListener.onReplied failed: {}", e.getMessage());
            }
        }
    }

    private void fireNoSpeaker(String userText) {
        for (ChatListener listener : listeners) {
            try {
                listener.onNoSpeaker(room, userText);
            } catch (RuntimeException e) {
                log.warn("ChatListener.onNoSpeaker failed: {}", e.getMessage());
            }
        }
    }

    private void fireError(ChatPersona speaker, String userText, String message, Throwable cause) {
        for (ChatListener listener : listeners) {
            try {
                listener.onError(room, speaker, userText, message, cause);
            } catch (RuntimeException e) {
                log.warn("ChatListener.onError failed: {}", e.getMessage());
            }
        }
    }

    public static final class Builder {

        private Room room;
        private SpeakerPolicy speakerPolicy;
        private ContextAssembler assembler;
        private ModelClient modelClient;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private ToolRegistry tools;
        private final List<ChatListener> listeners = new ArrayList<>();
        private ConsistencyGuard consistencyGuard = ConsistencyGuard.noop();

        public Builder room(Room room) {
            this.room = room;
            return this;
        }

        public Builder speakerPolicy(SpeakerPolicy speakerPolicy) {
            this.speakerPolicy = speakerPolicy;
            return this;
        }

        public Builder assembler(ContextAssembler assembler) {
            this.assembler = assembler;
            return this;
        }

        public Builder modelClient(ModelClient modelClient) {
            this.modelClient = modelClient;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder tools(ToolRegistry tools) {
            this.tools = tools;
            return this;
        }

        public Builder listener(ChatListener listener) {
            this.listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /**
         * Optional drift check after Done. {@code null} is {@link ConsistencyGuard#noop()}.
         */
        public Builder consistencyGuard(ConsistencyGuard consistencyGuard) {
            this.consistencyGuard = consistencyGuard == null
                    ? ConsistencyGuard.noop()
                    : consistencyGuard;
            return this;
        }

        public ChatEngine build() {
            return new ChatEngine(
                    room,
                    speakerPolicy,
                    assembler == null ? ContextAssembler.defaults() : assembler,
                    modelClient,
                    maxSteps,
                    tools,
                    listeners,
                    consistencyGuard);
        }
    }
}
