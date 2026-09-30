package io.github.qwzhang01.agent.rag.query;

import io.github.qwzhang01.agent.rag.QueryRewriter;
import io.github.qwzhang01.agent.rag.model.ConversationTurn;

import java.util.List;
import java.util.Objects;

/** Retrieves with the question as asked. */
public final class IdentityQueryRewriter implements QueryRewriter {

    public static final IdentityQueryRewriter INSTANCE = new IdentityQueryRewriter();

    @Override
    public Rewrite rewrite(String question, List<ConversationTurn> history) {
        return Rewrite.unchanged(Objects.requireNonNull(question, "question"));
    }
}
