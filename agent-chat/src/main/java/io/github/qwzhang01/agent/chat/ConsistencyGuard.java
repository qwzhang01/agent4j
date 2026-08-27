package io.github.qwzhang01.agent.chat;

/**
 * Optional drift check after a successful reply.
 * <p>
 * Input is the speaker (persona text is the host's anchor) plus this turn's
 * user line and reply. The engine does not own OOC rules or product personas;
 * a rule or LLM implementation lives with the host.
 * <p>
 * Default is {@link #noop()}: always OK, no alert.
 */
@FunctionalInterface
public interface ConsistencyGuard {

    /**
     * Inspect this turn. Must not mutate the room or rewrite {@code reply}.
     *
     * @return {@link ConsistencyVerdict#ok()} or a warning; {@code null} is treated as OK
     */
    ConsistencyVerdict check(Room room, ChatPersona speaker, String userText, String reply);

    static ConsistencyGuard noop() {
        return (room, speaker, userText, reply) -> ConsistencyVerdict.ok();
    }
}
