package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.run.RunContext;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reflection/Critique wrapper (Stage 9): a decorator around any
 * {@link Agent} that critiques each candidate answer and regenerates until
 * the critique passes or {@code maxCycles} is exhausted.
 * <p>
 * Contract — the two hard rules from the roadmap:
 * <ul>
 *   <li><b>Bounded reflection</b>: at most {@code maxCycles} critique
 *       cycles (default 2). When the last critique still rejects, the LAST
 *       candidate is returned as-is with a {@code ReflectionFinished}
 *       GIVE_UP event — the loop never runs away.</li>
 *   <li><b>Output separation</b>: critique text NEVER reaches the user.
 *       The critique runs on a private {@link AgentState} scratch copy;
 *       the delegate's own history stays untouched by reflection (the
 *       delegate's final answer is the user-visible output, unchanged).
 *       The {@link ReflectionStarted}/{@link ReflectionFinished} events
 *       carry cycle count and verdict only.</li>
 * </ul>
 * <p>
 * How it composes: wrap the <em>composed</em> agent (governed executor,
 * guardrails, handoffs already inside):
 * <pre>{@code
 * Agent reflective = new ReflectiveAgent(builtAgent, critiqueClient)
 *         .maxCycles(2)
 *         .critiquePrompt(ctx -> "...");
 * }</pre>
 * The critique model call is a plain model call (no tools) against the
 * SAME model client the loop uses — reflection is not a second agent with
 * its own tool surface.
 * <p>
 * Failure semantics: a critique call that throws does not fail the run —
 * reflection degrades to pass-through (the candidate stands, a
 * GIVE_UP-style verdict is recorded in the event). Reflection is an
 * enhancement, not a hard dependency; the wrapped agent's failure
 * taxonomy and recovery protocol stay authoritative.
 */
public final class ReflectiveAgent implements Agent {

    /** Default max critique cycles (roadmap: bounded reflection). */
    public static final int DEFAULT_MAX_CYCLES = 2;

    /** No-op listener for the run paths (events are a stream-only contract). */
    private static final Consumer<AgentEvent> NO_EVENTS = event -> { };

    private final Agent delegate;
    private final ModelClient critiqueModelClient;
    private int maxCycles = DEFAULT_MAX_CYCLES;
    private CritiquePrompt critiquePrompt = CritiquePrompt.defaultPrompt();

    /**
     * Critique prompt strategy: given the user question and candidate
     * answer, produce the message the critique model sees.
     */
    @FunctionalInterface
    public interface CritiquePrompt {

        /**
         * @param userQuestion the original user input
         * @param candidate    the candidate answer to critique
         * @return critique instruction for the critique model
         */
        String critiqueInstruction(String userQuestion, String candidate);

        /**
         * Default: strict verdict protocol. The critique model must answer
         * with exactly one word on the first line: PASS or REVISE, then
         * optionally reasons on following lines.
         */
        static CritiquePrompt defaultPrompt() {
            return (q, a) -> "You are a strict reviewer. Question: " + q
                    + "\n\nCandidate answer: " + a
                    + "\n\nReply with PASS or REVISE as the FIRST word of"
                    + " your response, then reasons on following lines."
                    + " PASS only when the answer is correct and complete.";
        }
    }

    public ReflectiveAgent(Agent delegate, ModelClient critiqueModelClient) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.critiqueModelClient = Objects.requireNonNull(critiqueModelClient, "critiqueModelClient");
    }

    public ReflectiveAgent maxCycles(int maxCycles) {
        if (maxCycles < 1) {
            throw new IllegalArgumentException("maxCycles must be >= 1, got " + maxCycles);
        }
        this.maxCycles = maxCycles;
        return this;
    }

    public ReflectiveAgent critiquePrompt(CritiquePrompt critiquePrompt) {
        this.critiquePrompt = Objects.requireNonNull(critiquePrompt, "critiquePrompt");
        return this;
    }

    @Override
    public String run(String userInput) {
        return run(userInput, new AgentState());
    }

    @Override
    public String run(String userInput, AgentState state) {
        return runInternal(userInput, state, null, NO_EVENTS);
    }

    @Override
    public String run(String userInput, AgentState state, RunContext ctx) {
        return runInternal(userInput, state, ctx, NO_EVENTS);
    }

    @Override
    public void stream(String userInput, AgentState state, Consumer<AgentEvent> listener) {
        // runInternal owns the single Done emission on every path.
        runInternal(userInput, state, null, listener);
    }

    /**
     * Ctx-aware streaming: the interface's ctx overloads take ChatMessage;
     * default methods forward String→ChatMessage→(our) ChatMessage path.
     */
    @Override
    public void stream(ChatMessage userMessage, AgentState state, Consumer<AgentEvent> listener,
                       RunContext ctx) {
        String input = userMessage.content() != null ? userMessage.content() : "";
        runInternal(input, state, ctx, listener);
    }

    @Override
    public String run(ChatMessage userMessage, AgentState state, RunContext ctx) {
        String input = userMessage.content() != null ? userMessage.content() : "";
        return runInternal(input, state, ctx, NO_EVENTS);
    }

    @Override
    public String run(ChatMessage userMessage, AgentState state) {
        String input = userMessage.content() != null ? userMessage.content() : "";
        return runInternal(input, state, null, NO_EVENTS);
    }

    /**
     * Shared engine: run delegate → critique → (maybe) regenerate. The
     * delegate's {@code Done} events during reflection cycles are
     * swallowed (regeneration is internal); only the final accepted answer
     * gets a user-visible {@code Done}.
     */
    private String runInternal(String userInput, AgentState state, RunContext ctx,
                               Consumer<AgentEvent> listener) {
        // Cycle 0 is the delegate's own answer; cycles 1..maxCycles are
        // critique→regenerate passes.
        String candidate = runDelegate(userInput, state, ctx);
        if (isFailure(state)) {
            // Delegate failed: reflection has nothing to critique. The
            // delegate's own failure taxonomy and error state stand.
            listener.accept(new AgentEvent.Done(candidate, state));
            return candidate;
        }

        for (int cycle = 1; cycle <= maxCycles; cycle++) {
            listener.accept(new AgentEvent.ReflectionStarted(cycle, maxCycles));
            Verdict verdict = critique(userInput, candidate);
            if (verdict == Verdict.PASS) {
                listener.accept(new AgentEvent.ReflectionFinished(cycle, "PASS"));
                listener.accept(new AgentEvent.Done(candidate, state));
                return candidate;
            }
            if (verdict == Verdict.ERROR) {
                // Critique infrastructure failed: pass-through, candidate
                // stands. Reflection is an enhancement, not a dependency.
                listener.accept(new AgentEvent.ReflectionFinished(cycle, "GIVE_UP"));
                listener.accept(new AgentEvent.Done(candidate, state));
                return candidate;
            }
            listener.accept(new AgentEvent.ReflectionFinished(cycle, "REVISE"));
            // REVISE: regenerate. State keeps the delegate's history — the
            // critique is NOT written into it (output separation).
            candidate = runDelegate(userInput, state, ctx);
            if (isFailure(state)) {
                listener.accept(new AgentEvent.Done(candidate, state));
                return candidate;
            }
        }
        // Exhausted cycles: last candidate stands, honestly recorded.
        listener.accept(new AgentEvent.ReflectionFinished(maxCycles, "GIVE_UP"));
        listener.accept(new AgentEvent.Done(candidate, state));
        return candidate;
    }

    private boolean isFailure(AgentState state) {
        return state.getStatus() == AgentState.Status.ERROR
                || state.getStatus() == AgentState.Status.MAX_STEPS_EXCEEDED;
    }

    private String runDelegate(String userInput, AgentState state, RunContext ctx) {
        return ctx != null
                ? delegate.run(userInput, state, ctx)
                : delegate.run(userInput, state);
    }

    private enum Verdict { PASS, REVISE, ERROR }

    /**
     * Runs one critique pass on a private scratch state (never the
     * delegate's history). Returns PASS / REVISE / ERROR.
     */
    private Verdict critique(String userQuestion, String candidate) {
        try {
            ModelRequest request = ModelRequest.builder()
                    .model("")
                    .addMessage(ChatMessage.user(
                            critiquePrompt.critiqueInstruction(userQuestion, candidate)))
                    .build();
            ModelResponse response = critiqueModelClient.chat(request);
            String text = response == null || response.content() == null
                    ? "" : response.content();
            String firstWord = text.trim().toUpperCase();
            if (firstWord.startsWith("PASS")) {
                return Verdict.PASS;
            }
            if (firstWord.startsWith("REVISE")) {
                return Verdict.REVISE;
            }
            // Unparseable critique: treat as revise-once-more? No — treat
            // as ERROR (pass-through). An unparseable verdict must not
            // silently loop the budget away.
            return Verdict.ERROR;
        } catch (Exception e) {
            return Verdict.ERROR;
        }
    }

    @Override
    public AgentConfig getConfig() {
        return delegate.getConfig();
    }
}
