package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.context.ContextAssembler;
import io.github.qwzhang01.agent.chat.context.ExtraTextSource;
import io.github.qwzhang01.agent.chat.context.MemorySource;
import io.github.qwzhang01.agent.chat.guard.ConsistencyGuard;
import io.github.qwzhang01.agent.chat.guard.ConsistencyVerdict;
import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.model.RoomMessage;
import io.github.qwzhang01.agent.chat.retry.RetryPolicy;
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
    private final RetryPolicy retryPolicy;

    ChatEngine(Room room, SpeakerPolicy speakerPolicy, ContextAssembler assembler,
               ModelClient modelClient, int maxSteps, ToolRegistry tools,
               List<ChatListener> listeners, ConsistencyGuard consistencyGuard,
               RetryPolicy retryPolicy) {
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
        this.retryPolicy = retryPolicy == null ? RetryPolicy.never() : retryPolicy;
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
     * <p>
     * Emits a {@link AgentEvent.TurnTrace} immediately before the final
     * {@link AgentEvent.Done} so that listeners can audit context, cost, and timing.
     * <p>
     * When a {@link RetryPolicy} is configured, replies that trigger
     * {@link RetryPolicy#shouldRetry} are regenerated (up to
     * {@link RetryPolicy#maxAttempts()} times). Each attempt streams its
     * {@link AgentEvent.ContentDelta}s through {@code listener}; before a discarded
     * attempt is retried, an {@link AgentEvent.RetryStarted} event is emitted so
     * the host can reset any partial rendering. Only the accepted reply's TurnTrace and
     * Done are emitted.
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
        streamForced(picked.get(), userText, userText, listener);
    }

    /**
     * One reply for a FORCED speaker: no user-message append (the caller
     * already put it on history) and no speaker pick. Ensemble engines
     * (see {@code EnsembleChatEngine}) use this for continuation beats,
     * passing a stage-direction {@code inputText} while keeping the original
     * {@code userText} for context sources, listeners, and traces.
     * <p>
     * Everything else matches {@link #stream}: retry policy, TurnTrace,
     * history append, ConsistencyGuard, listener callbacks, Done.
     */
    public void streamForced(ChatPersona speaker, String userText, String inputText,
                             Consumer<AgentEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(speaker, "speaker");
        if (userText == null || inputText == null) {
            throw new IllegalArgumentException("userText and inputText must not be null");
        }

        long startNanos = System.nanoTime();
        // Base prefix: assembled once; retry attempts may append retryExtraText.
        var prepared = assembler.prepare(room, speaker, userText);
        List<ChatMessage> basePrefix = prepared.prefix();

        String finalReply = "";
        List<ChatMessage> finalPrefix = basePrefix;
        AgentState finalState = new AgentState();
        int retriesDone = 0;

        while (true) {
            List<ChatMessage> attemptPrefix = buildAttemptPrefix(basePrefix, retriesDone);
            AgentState state = new AgentState();
            prepared.history().forEach(state::addMessage);
            int historySize = prepared.history().size();

            AgentConfig config = new AgentConfig(
                    speaker.personaId(), prepared.systemPrompt(), modelClient, tools, maxSteps,
                    (activeConfig, currentState) -> {
                        // Keep retrieved context out of state; include each new tool round once.
                        List<ChatMessage> context = new ArrayList<>(attemptPrefix);
                        context.addAll(currentState.getMessages().subList(
                                historySize, currentState.getMessages().size()));
                        return context;
                    });
            SimpleAgent agent = new SimpleAgent(config);

            final String[] replyHolder = {""};
            final boolean[] errorOccurred = {false};

            try {
                agent.stream(ChatMessage.user(inputText), state, event -> {
                    if (event instanceof AgentEvent.Done done) {
                        replyHolder[0] = done.finalAnswer() == null ? "" : done.finalAnswer();
                        // Done is NOT forwarded here; emitted once at the end of all retries.
                    } else if (event instanceof AgentEvent.Error err) {
                        fireError(speaker, inputText, err.message(), err.cause());
                        errorOccurred[0] = true;
                        emitHost(listener, event);
                    } else {
                        emitHost(listener, event);
                    }
                });
            } catch (RuntimeException e) {
                log.error("chat stream failed in room '{}': {}", room.roomId(), e.getMessage());
                fireError(speaker, inputText, e.getMessage(), e);
                emitHost(listener, new AgentEvent.Error(
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e));
                return;
            }

            if (errorOccurred[0]) {
                return;  // Do not retry on model errors.
            }

            finalReply = replyHolder[0];
            finalPrefix = new ArrayList<>();
            if (prepared.systemPrompt() != null && !prepared.systemPrompt().isBlank()) {
                finalPrefix.add(ChatMessage.system(prepared.systemPrompt()));
            }
            finalPrefix.addAll(attemptPrefix);
            finalState = state;

            boolean shouldRetry = retryPolicy.shouldRetry(finalReply, retriesDone);
            if (!shouldRetry || retriesDone >= retryPolicy.maxAttempts()) {
                break;
            }
            retriesDone++;
            log.info("ChatEngine retry {}/{} in room '{}'",
                    retriesDone, retryPolicy.maxAttempts(), room.roomId());
            // streamed: the next ContentDelta belongs to a brand-new attempt.
            emitHost(listener, new AgentEvent.RetryStarted(
                    finalReply, retriesDone + 1, retryPolicy.maxAttempts()));
        }

        // Emit TurnTrace (before Done), update room history, fire listeners, emit Done.
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        emitHost(listener, buildTurnTrace(speaker, finalPrefix, finalReply, latencyMs));
        room.append(RoomMessage.assistant(speaker.personaId(), finalReply));
        checkConsistency(speaker, userText, finalReply);
        fireReplied(speaker, userText, finalReply);
        emitHost(listener, new AgentEvent.Done(finalReply, finalState));
    }

    /** Host listener is a side channel: a gone SSE client must not fail the turn. */
    static void emitHost(Consumer<AgentEvent> listener, AgentEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            log.warn("host listener failed: {}", e.toString());
        }
    }

    /**
     * Returns the base prefix for the first attempt, or base + retryExtraText for retries.
     */
    private List<ChatMessage> buildAttemptPrefix(List<ChatMessage> basePrefix, int retriesDone) {
        if (retriesDone == 0) {
            return basePrefix;
        }
        String extra = retryPolicy.retryExtraText();
        if (extra == null || extra.isBlank()) {
            return basePrefix;
        }
        List<ChatMessage> augmented = new ArrayList<>(basePrefix);
        augmented.add(ChatMessage.system(extra));
        return augmented;
    }

    private AgentEvent.TurnTrace buildTurnTrace(ChatPersona speaker, List<ChatMessage> prefix,
                                                 String reply, long latencyMs) {
        List<String> recalledSubjects = assembler.sources().stream()
                .filter(source -> source instanceof MemorySource)
                .flatMap(source -> ((MemorySource) source).lastRecalledSubjects().stream())
                .toList();
        int extraBytes = assembler.sources().stream()
                .filter(source -> source instanceof ExtraTextSource)
                .mapToInt(source -> ((ExtraTextSource) source).lastOutputBytes())
                .sum();
        int promptChars = prefix.stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length())
                .sum();
        return new AgentEvent.TurnTrace(
                speaker.version(),
                recalledSubjects,
                extraBytes,
                promptChars,
                reply.length(),
                latencyMs);
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
        private RetryPolicy retryPolicy = RetryPolicy.never();

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

        /**
         * Optional retry policy for hard-label violations detected post-completion.
         * {@code null} defaults to {@link RetryPolicy#never()} (no retries).
         */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy == null ? RetryPolicy.never() : retryPolicy;
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
                    consistencyGuard,
                    retryPolicy);
        }
    }
}
