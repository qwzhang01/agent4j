package io.github.qwzhang01.agent.tavern.event;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@code trigger_event} - a character may deliberately set a story event in
 * motion (Stage 16 M16.3): raising a toast to trigger "crowd cheers",
 * picking a fight to trigger "the guards step in".
 * <p>
 * Manual triggering bypasses the rule CONDITION (the character's dramatic
 * intent is the authorization) but not the ONCE bookkeeping. The event is
 * QUEUED to the engine's settlement batch, never executed inline: firing
 * inline would nest an event-response agent run inside the current run -
 * a recursion storm. Consequences unfold at settlement, in the same turn.
 */
public final class TriggerEventTool implements Tool {

    public static final String NAME = "trigger_event";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "eventId": { "type": "string", "description": "Id of the story event to set in motion, e.g. 'crowd-cheers'" }
              },
              "required": ["eventId"]
            }
            """;

    private final EventEvaluator evaluator;
    private final Consumer<EventEvaluator.TriggeredEvent> pendingSink;

    /**
     * @param evaluator   the rule table (id lookup + once bookkeeping)
     * @param pendingSink where queued events wait for the settlement batch
     */
    public TriggerEventTool(EventEvaluator evaluator,
                            Consumer<EventEvaluator.TriggeredEvent> pendingSink) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.pendingSink = Objects.requireNonNull(pendingSink, "pendingSink must not be null");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Deliberately set a known story event in motion (e.g. raising a toast, "
                + "starting a song). Use it when the moment genuinely calls for it; "
                + "the consequences unfold at the end of the turn.";
    }

    @Override
    public String getParametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        if (arguments == null || !arguments.hasNonNull("eventId")) {
            throw new ToolException("trigger_event requires a non-null 'eventId'");
        }
        String eventId = arguments.get("eventId").asText();
        var triggered = evaluator.triggerManually(eventId);
        if (triggered.isEmpty()) {
            return "[REJECTED] No triggerable event '" + eventId
                    + "' (unknown id, or it already happened). Choose another course.";
        }
        pendingSink.accept(triggered.get());
        return "Event '" + eventId + "' is in motion: " + triggered.get().event().description()
                + " Its consequences unfold at the end of this turn.";
    }
}
