package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.RagAnswer;

import java.util.List;

/**
 * What {@link AnswerEvaluator} needs from one answered case, independent of the pipeline.
 *
 * @param refused   whether the system declined for lack of material
 * @param sentences verified answer sentences
 * @param answer    answer text as shown to the user
 */
public record AnswerOutcome(boolean refused, List<AnswerSentence> sentences, String answer) {

    public AnswerOutcome {
        sentences = sentences == null ? List.of() : List.copyOf(sentences);
        answer = answer == null ? "" : answer;
    }

    public static AnswerOutcome of(RagAnswer answer) {
        return new AnswerOutcome(answer.refused(), answer.sentences(), answer.answer());
    }
}
