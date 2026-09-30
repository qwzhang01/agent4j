package io.github.qwzhang01.agent.rag.model;

import java.util.Objects;

/**
 * One prior turn of a multi-turn conversation.
 *
 * @param role    {@code user} or {@code assistant}
 * @param content message text
 */
public record ConversationTurn(String role, String content) {

    public ConversationTurn {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }

    public static ConversationTurn user(String content) {
        return new ConversationTurn("user", content);
    }

    public static ConversationTurn assistant(String content) {
        return new ConversationTurn("assistant", content);
    }
}
