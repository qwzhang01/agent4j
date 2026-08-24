package io.github.qwzhang01.agent.tavern.world;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@code set_world_flag} - the character's handle on the world (Stage 16 M16.2,
 * blueprint D4: "influence is a tool").
 * <p>
 * This tool does NOT apply the effect. It submits a {@link WorldEffect.SetFlag}
 * instruction to the engine-provided sink; the turn engine is the single place
 * where effects are applied and recorded (blueprint D3: one apply point).
 * The tool is therefore a pure instruction submitter - trivially testable with
 * a collecting sink, and replay-safe by construction.
 * <p>
 * Governance (Stage 9 chain: permission + audit) plugs in at the executor level
 * in M16.3 together with the relationship limiter - the two-layer split is
 * "governance decides IF the call may happen, the tool's domain validates
 * WHAT the call contains".
 */
public final class SetWorldFlagTool implements Tool {

    public static final String NAME = "set_world_flag";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "key":   { "type": "string", "description": "Name of the world flag to set, e.g. 'bard-mood'" },
                "value": { "type": "string", "description": "Value to set, e.g. 'lively'" }
              },
              "required": ["key", "value"]
            }
            """;

    private final Consumer<WorldEffect> effectSink;

    /**
     * @param effectSink where submitted effects go (the turn engine's apply point)
     */
    public SetWorldFlagTool(Consumer<WorldEffect> effectSink) {
        this.effectSink = Objects.requireNonNull(effectSink, "effectSink must not be null");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Set a named flag in the game world to mark a scene change "
                + "(e.g. the bard's mood turns lively, a quarrel starts). "
                + "World changes should grow naturally out of the dialogue.";
    }

    @Override
    public String getParametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        if (arguments == null || !arguments.hasNonNull("key") || !arguments.hasNonNull("value")) {
            throw new ToolException("set_world_flag requires non-null 'key' and 'value'");
        }
        String key = arguments.get("key").asText();
        String value = arguments.get("value").asText();
        WorldEffect effect;
        try {
            effect = new WorldEffect.SetFlag(key, value);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        effectSink.accept(effect);
        return "World flag '" + key + "' set to '" + value + "'.";
    }
}
