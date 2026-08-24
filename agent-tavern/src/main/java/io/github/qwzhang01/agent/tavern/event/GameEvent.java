package io.github.qwzhang01.agent.tavern.event;

/**
 * A story event - a dramatic fact that happens in the game (Stage 16 M16.3,
 * blueprint D5).
 * <p>
 * Boundary note (blueprint §1): a GameEvent is a PLOT FACT (data: what
 * happened and who is drawn in), not an EventBroker event (mechanism:
 * fire -&gt; resume a workflow run) and not a {@code MemoryType.EVENT} entry
 * (memory: remembering that something happened). Three same-named things,
 * three different layers.
 *
 * @param eventId            stable id, also the manual-trigger handle
 * @param description        what happens, phrased for the model to play out
 * @param respondCharacterId optional: a character who reacts out loud when
 *                           this event fires (null = silent world change)
 */
public record GameEvent(String eventId, String description, String respondCharacterId) {

    public GameEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be null or blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be null or blank");
        }
    }
}
