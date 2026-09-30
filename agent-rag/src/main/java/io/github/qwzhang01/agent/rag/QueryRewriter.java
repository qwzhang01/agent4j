package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.ConversationTurn;

import java.util.List;

/**
 * Turns a context-dependent follow-up ("那引用溯源怎么做") into a standalone retrieval
 * query ("RAG 中引用溯源的实现方案").
 */
public interface QueryRewriter {

    /**
     * @param question current question
     * @param history  prior turns, oldest first
     * @return standalone query; the original question when no rewrite is needed or rewriting fails
     */
    Rewrite rewrite(String question, List<ConversationTurn> history);

    /**
     * @param query            query to retrieve with
     * @param promptTokens     tokens spent, 0 when no model call was made
     * @param completionTokens tokens spent, 0 when no model call was made
     * @param degraded         true when rewriting failed and the original question was returned
     */
    record Rewrite(String query, int promptTokens, int completionTokens, boolean degraded) {
        public static Rewrite unchanged(String question) {
            return new Rewrite(question, 0, 0, false);
        }
    }
}
