package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.model.ConversationTurn;

import java.util.List;
import java.util.Objects;

/**
 * One evaluation question.
 *
 * @param id         unique case id
 * @param type       {@code single_hop}, {@code multi_turn}, {@code multi_hop}, {@code conflict},
 *                   {@code unanswerable}; other values are kept as-is
 * @param question   current question
 * @param history    prior turns, oldest first; empty for single-turn cases
 * @param relevant   gold locations; empty for unanswerable cases
 * @param answer     reference answer, may be empty
 * @param answerable whether the corpus covers the question
 */
public record EvalCase(
        String id,
        String type,
        String question,
        List<ConversationTurn> history,
        List<GoldRef> relevant,
        String answer,
        boolean answerable
) {
    public static final String SINGLE_HOP = "single_hop";
    public static final String MULTI_TURN = "multi_turn";
    public static final String MULTI_HOP = "multi_hop";
    public static final String CONFLICT = "conflict";
    public static final String UNANSWERABLE = "unanswerable";

    public EvalCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(question, "question");
        type = type == null || type.isBlank() ? "unknown" : type;
        history = history == null ? List.of() : List.copyOf(history);
        relevant = relevant == null ? List.of() : List.copyOf(relevant);
        answer = answer == null ? "" : answer;
    }
}
