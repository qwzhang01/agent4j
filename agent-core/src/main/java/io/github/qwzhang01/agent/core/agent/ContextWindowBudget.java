package io.github.qwzhang01.agent.core.agent;

/**
 * Context window budget: the four-account model (KP2).
 * <p>
 * Every request to a model consumes tokens from a finite context window.
 * The window is a budget, not a container: callers must explicitly account
 * for all four spending categories before they can know how many tokens
 * remain for conversation history.
 * <pre>
 *   totalWindowTokens
 *   ├── systemReserve      — system prompt (stable, does not grow with turns)
 *   ├── toolSchemaReserve  — tool / handoff definitions (stable per config)
 *   ├── outputHeadroom     — reserved for model output (never consumed by input)
 *   └── historyBudget()    — what is left: conversation history
 * </pre>
 * Design principle: the four-account split is an explicit product decision.
 * "Who to cut when over budget" is a product姿态:
 * <ul>
 *   <li>Cutting history → amnesia, but conversation continues.</li>
 *   <li>Cutting tools → crippled agent, usually wrong.</li>
 *   <li>Cutting output headroom → silent truncation of answers, hard to debug.</li>
 *   <li>Refusing to serve → honest, but disrupts UX.</li>
 * </ul>
 * v1 default (implemented by {@link ContextWindowEnforcer}): drop oldest history
 * messages until the history estimate fits within {@link #historyBudget()}.
 * <p>
 * Token estimation uses the {@code chars / 4} heuristic throughout (no tokenizer
 * dependency; consistent with {@code ContextBudget} in agent-memory).
 * <p>
 * Construction: use {@link #of(int, int, int, int)} for explicit slot sizes, or
 * {@link #forWindow(int)} for the opinionated defaults (10 / 15 / 20 / 55 split).
 */
public record ContextWindowBudget(
        int totalWindowTokens,
        int systemReserve,
        int toolSchemaReserve,
        int outputHeadroom
) {

    public ContextWindowBudget {
        if (totalWindowTokens <= 0) {
            throw new IllegalArgumentException("totalWindowTokens must be positive: " + totalWindowTokens);
        }
        if (systemReserve < 0 || toolSchemaReserve < 0 || outputHeadroom < 0) {
            throw new IllegalArgumentException("budget slots must not be negative");
        }
        // Use local params here — compact constructor body runs before implicit this.field = param assignment
        int history = totalWindowTokens - systemReserve - toolSchemaReserve - outputHeadroom;
        if (history < 0) {
            throw new IllegalArgumentException(
                    "system + tools + output reserves (%d + %d + %d = %d) exceed totalWindowTokens (%d)"
                            .formatted(systemReserve, toolSchemaReserve, outputHeadroom,
                                    systemReserve + toolSchemaReserve + outputHeadroom,
                                    totalWindowTokens));
        }
    }

    /**
     * Tokens available for conversation history.
     * <p>
     * {@code totalWindowTokens - systemReserve - toolSchemaReserve - outputHeadroom}
     * <p>
     * {@link ContextWindowEnforcer} truncates oldest messages until the estimated
     * history cost fits within this value.
     */
    public int historyBudget() {
        return historyBudgetUnchecked();
    }

    private int historyBudgetUnchecked() {
        return totalWindowTokens - systemReserve - toolSchemaReserve - outputHeadroom;
    }

    /**
     * Explicit four-slot constructor.
     *
     * @param totalWindowTokens  model context window size (e.g. 128_000 for Claude Sonnet)
     * @param systemReserve      tokens reserved for the system prompt
     * @param toolSchemaReserve  tokens reserved for tool / handoff schemas
     * @param outputHeadroom     tokens reserved for model output
     */
    public static ContextWindowBudget of(int totalWindowTokens,
                                         int systemReserve,
                                         int toolSchemaReserve,
                                         int outputHeadroom) {
        return new ContextWindowBudget(totalWindowTokens, systemReserve, toolSchemaReserve, outputHeadroom);
    }

    /**
     * Opinionated defaults: 10 % system · 15 % tools · 20 % output · 55 % history.
     * <p>
     * Rule of thumb sizing:
     * <ul>
     *   <li>System prompt rarely exceeds 2–4 K tokens; 10 % of a 128 K window = 12 800 is generous.</li>
     *   <li>Each tool schema is roughly 300–500 tokens; 15 % accommodates ~40 tools.</li>
     *   <li>20 % output headroom supports up to ~25 600 tokens of response on a 128 K window.</li>
     *   <li>The remaining 55 % is history — compressible, truncatable, the right place to trim.</li>
     * </ul>
     * Override with {@link #of} when the model or toolset has a different profile.
     *
     * @param totalWindowTokens the model's published context window size
     */
    public static ContextWindowBudget forWindow(int totalWindowTokens) {
        int system = totalWindowTokens / 10;          // 10 %
        int tools = (int) (totalWindowTokens * 0.15); // 15 %
        int output = totalWindowTokens / 5;            // 20 %
        return new ContextWindowBudget(totalWindowTokens, system, tools, output);
    }

    /** Common preset: 128 K window (Claude Sonnet 3.x / GPT-4o). */
    public static ContextWindowBudget window128k() {
        return forWindow(128_000);
    }

    /** Common preset: 32 K window (older GPT-4 / smaller models). */
    public static ContextWindowBudget window32k() {
        return forWindow(32_000);
    }

    /** Common preset: 8 K window (local / smaller open-source models). */
    public static ContextWindowBudget window8k() {
        return forWindow(8_000);
    }

    @Override
    public String toString() {
        return "ContextWindowBudget{total=%d, system=%d, tools=%d, output=%d, history=%d}"
                .formatted(totalWindowTokens, systemReserve, toolSchemaReserve, outputHeadroom, historyBudget());
    }
}
