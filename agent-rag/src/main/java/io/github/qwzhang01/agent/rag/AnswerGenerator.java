package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.ConversationTurn;
import io.github.qwzhang01.agent.rag.model.GeneratedAnswer;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;

import java.util.List;

/**
 * Produces an answer grounded only in {@code contexts}, citing chunk ids inline as
 * {@code [chunkId]}, and refusing when the contexts do not cover the question.
 */
public interface AnswerGenerator {

    GeneratedAnswer generate(String question, List<ConversationTurn> history, List<ScoredChunk> contexts);
}
