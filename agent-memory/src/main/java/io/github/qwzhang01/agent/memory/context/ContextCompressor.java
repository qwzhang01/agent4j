package io.github.qwzhang01.agent.memory.context;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Pi-style context compaction (Stage 8 D4).
 * <p>
 * When the message history exceeds the token budget, the oldest messages
 * (excluding system prompt and the most recent K messages) are summarized
 * into a single {@code user} message by the model. The result:
 * <pre>
 *   [system..., summary(user), ...recent K messages]
 * </pre>
 * The original messages that were compressed are returned in
 * {@link CompressionResult#archived()} so the caller can persist them
 * to {@link io.github.qwzhang01.agent.memory.MemoryStore} as a
 * {@link io.github.qwzhang01.agent.memory.MemoryType#SUMMARY} entry (for audit).
 */
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);

    private static final String COMPACTION_PROMPT = """
            Summarize the following conversation history concisely.
            Preserve all key facts, decisions, user preferences, tool results, and unresolved questions.
            Do not add any information not present in the history.

            Conversation to summarize:
            """;

    private final ModelClient modelClient;
    private final int budgetTokens;
    private final int keepRecent;

    /**
     * @param modelClient  used for the summarization call (can be a cheap model)
     * @param budgetTokens token budget; compaction triggers when exceeded
     * @param keepRecent   number of most-recent non-system messages to keep verbatim
     */
    public ContextCompressor(ModelClient modelClient, int budgetTokens, int keepRecent) {
        this.modelClient = modelClient;
        this.budgetTokens = budgetTokens;
        this.keepRecent = keepRecent;
    }

    /**
     * Compress the message list if it exceeds the budget.
     *
     * @param messages current message history
     * @return compression result (compressed == false means no action was taken)
     */
    public CompressionResult compress(List<ChatMessage> messages) {
        if (!ContextBudget.exceeds(messages, budgetTokens)) {
            return new CompressionResult(new ArrayList<>(messages), List.of(), false);
        }
        List<ChatMessage> systemMsgs = new ArrayList<>();
        List<ChatMessage> nonSystem = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (msg.role() == ChatRole.SYSTEM) {
                systemMsgs.add(msg);
            } else {
                nonSystem.add(msg);
            }
        }

        // Nothing to compress if we don't have more than keepRecent non-system messages
        if (nonSystem.size() <= keepRecent) {
            return new CompressionResult(new ArrayList<>(messages), List.of(), false);
        }

        int archiveEnd = nonSystem.size() - keepRecent;
        List<ChatMessage> toArchive = new ArrayList<>(nonSystem.subList(0, archiveEnd));
        List<ChatMessage> recent = new ArrayList<>(nonSystem.subList(archiveEnd, nonSystem.size()));

        // Summarize the archived segment
        String summary = summarize(toArchive);
        ChatMessage summaryMsg = ChatMessage.user(
                "[Summary of earlier conversation]\n" + summary);

        // Reassemble: [system..., summary, ...recent]
        List<ChatMessage> compressed = new ArrayList<>(systemMsgs);
        compressed.add(summaryMsg);
        compressed.addAll(recent);

        log.info("Compacted {} messages into 1 summary (budget={}, before={}tok, after={}tok)",
                toArchive.size(), budgetTokens,
                ContextBudget.estimate(messages), ContextBudget.estimate(compressed));

        return new CompressionResult(compressed, toArchive, true);
    }

    private String summarize(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder(COMPACTION_PROMPT);
        for (ChatMessage msg : messages) {
            sb.append("\n[").append(msg.role()).append("] ");
            if (msg.content() != null && !msg.content().isBlank()) {
                sb.append(msg.content());
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                sb.append(" (tool calls: ");
                for (var tc : msg.toolCalls()) {
                    sb.append(tc.name()).append(" ");
                }
                sb.append(")");
            }
        }

        ModelRequest request = ModelRequest.builder()
                .messages(List.of(
                        ChatMessage.system("You are a conversation summarizer. Output only the summary."),
                        ChatMessage.user(sb.toString())
                ))
                .maxTokens(512)
                .build();

        try {
            ModelResponse response = modelClient.chat(request);
            return response.content() != null ? response.content() : "(summary unavailable)";
        } catch (Exception e) {
            log.warn("Compaction summarization failed, using raw truncation: {}", e.getMessage());
            // Fallback: crude truncation rather than failing the whole run
            return "[Compaction failed - first 500 chars] " + sb.substring(0, Math.min(sb.length(), 500));
        }
    }

    /**
     * Result of a compaction attempt.
     *
     * @param compressed the resulting message list (same as input if not compressed)
     * @param archived   the original messages that were folded into the summary (empty if not compressed)
     * @param compressed whether compaction actually happened
     */
    public record CompressionResult(
            List<ChatMessage> compressed,
            List<ChatMessage> archived,
            boolean didCompress
    ) {
    }
}
