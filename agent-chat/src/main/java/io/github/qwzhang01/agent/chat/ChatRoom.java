package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.context.ContextAssembler;
import io.github.qwzhang01.agent.chat.context.ContextSource;
import io.github.qwzhang01.agent.chat.speaker.MentionSpeaker;
import io.github.qwzhang01.agent.chat.speaker.SoloSpeaker;
import io.github.qwzhang01.agent.chat.speaker.SpeakerPolicy;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Host-facing facade: personas + model + policy + sources become one room.
 */
public final class ChatRoom {

    private final ChatEngine engine;

    ChatRoom(ChatEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Room room() {
        return engine.room();
    }

    public ChatEngine engine() {
        return engine;
    }

    public String say(String userText) {
        return engine.say(userText);
    }

    public void stream(String userText, Consumer<AgentEvent> listener) {
        engine.stream(userText, listener);
    }

    public static final class Builder {

        private String roomId;
        private final List<ChatPersona> personas = new ArrayList<>();
        private final List<ContextSource> sources = new ArrayList<>();
        private final List<ChatListener> listeners = new ArrayList<>();
        private SpeakerPolicy speakerPolicy;
        private ContextAssembler assembler;
        private ModelClient modelClient;
        private int maxSteps = ChatEngine.DEFAULT_MAX_STEPS;
        private ToolRegistry tools;
        private RoomIdentity identity = RoomIdentity.empty();
        private ConsistencyGuard consistencyGuard = ConsistencyGuard.noop();

        public Builder roomId(String roomId) {
            this.roomId = roomId;
            return this;
        }

        public Builder persona(ChatPersona persona) {
            this.personas.add(Objects.requireNonNull(persona, "persona"));
            return this;
        }

        public Builder personas(List<ChatPersona> personas) {
            this.personas.addAll(Objects.requireNonNull(personas, "personas"));
            return this;
        }

        public Builder speakerPolicy(SpeakerPolicy speakerPolicy) {
            this.speakerPolicy = speakerPolicy;
            return this;
        }

        public Builder source(ContextSource source) {
            this.sources.add(Objects.requireNonNull(source, "source"));
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
         * Opaque memory-scope strings for this room.
         * {@link io.github.qwzhang01.agent.chat.context.MemorySource} with no
         * explicit list inherits them.
         */
        public Builder identity(RoomIdentity identity) {
            this.identity = identity == null ? RoomIdentity.empty() : identity;
            return this;
        }

        public Builder scopes(List<String> scopes) {
            return identity(RoomIdentity.of(scopes));
        }

        public Builder scopes(String... scopes) {
            return identity(RoomIdentity.of(scopes));
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

        public ChatRoom build() {
            Room room = new Room(roomId, personas, identity);
            SpeakerPolicy policy = speakerPolicy != null
                    ? speakerPolicy
                    : (personas.size() == 1 ? new SoloSpeaker() : new MentionSpeaker());
            ContextAssembler assembled = assembler != null
                    ? assembler
                    : (sources.isEmpty()
                    ? ContextAssembler.defaults()
                    : new ContextAssembler(sources));
            ChatEngine.Builder engine = ChatEngine.builder()
                    .room(room)
                    .speakerPolicy(policy)
                    .assembler(assembled)
                    .modelClient(modelClient)
                    .maxSteps(maxSteps)
                    .tools(tools);
            listeners.forEach(engine::listener);
            engine.consistencyGuard(consistencyGuard);
            return new ChatRoom(engine.build());
        }
    }
}
