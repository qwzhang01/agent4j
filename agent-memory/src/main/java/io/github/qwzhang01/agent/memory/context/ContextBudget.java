package io.github.qwzhang01.agent.memory.context;

import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * Token budget estimation (Stage 8 D4).
 * <p>
 * v1 uses a simple chars/4 heuristic (no tokenizer dependency).
 * This is intentionally approximate - the goal is to trigger compaction
 * before the context window overflows, not to be token-exact.
 */
public final class ContextBudget {

    private ContextBudget() {
    }

    /**
     * Estimate the token count of a message list.
     * Counts content length + tool call payloads, divides by 4.
     */
    public static int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int chars = 0;
        for (ChatMessage msg : messages) {
            if (msg.content() != null) {
                chars += msg.content().length();
            }
            if (msg.toolCalls() != null) {
                for (var tc : msg.toolCalls()) {
                    if (tc.name() != null) {
                        chars += tc.name().length();
                    }
                    if (tc.arguments() != null) {
                        chars += tc.arguments().toString().length();
                    }
                }
            }
        }
        return chars / 4;
    }

    /**
     * Whether the estimated token count exceeds the budget.
     */
    public static boolean exceeds(List<ChatMessage> messages, int budgetTokens) {
        return estimate(messages) > budgetTokens;
    }
}
