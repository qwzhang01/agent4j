package io.github.qwzhang01.agent.core.agent;

/**
 * A declared handoff: a special tool that lets the model transfer the
 * conversation to another agent mid-run.
 * <p>
 * Handoffs are declared on {@link AgentConfig} at assembly time. The loop
 * exposes each declared handoff as a tool ({@code transfer_to_<name>} by
 * default); when the model calls it, the loop swaps the active config
 * (persona, model client, tools, context builder) while the shared
 * {@link AgentState} — history and the global step budget — stays.
 * <p>
 * {@link #inputFilter()} trims what the <em>next</em> model request carries
 * (Decision 24 P2). It does not rewrite {@link AgentState}. Default is
 * {@link HandoffInputFilter#IDENTITY} (full carry).
 *
 * @param target      the agent config to transfer to; must not be the
 *                    declaring config itself (validated by {@code AgentConfig})
 * @param toolName    the tool name the model calls to trigger the transfer
 * @param description sent to the model inside the tool schema; clarity
 *                    matters — it tells the model WHEN to transfer
 * @param inputFilter request-boundary history filter for the target; null
 *                    is normalized to {@link HandoffInputFilter#IDENTITY}
 */
public record HandoffSpec(AgentConfig target, String toolName, String description,
                          HandoffInputFilter inputFilter) {

    public HandoffSpec {
        if (target == null) {
            throw new IllegalArgumentException("handoff target must not be null");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("handoff toolName must not be blank");
        }
        if (description == null || description.isBlank()) {
            description = "Transfer the conversation to agent '" + target.getName() + "'.";
        }
        if (inputFilter == null) {
            inputFilter = HandoffInputFilter.IDENTITY;
        }
    }

    /** Three-arg form: full-carry filter. Kept so existing call sites compile. */
    public HandoffSpec(AgentConfig target, String toolName, String description) {
        this(target, toolName, description, null);
    }

    /**
     * Declares a handoff with the default tool name
     * {@code transfer_to_<targetName>} and a generic description.
     */
    public static HandoffSpec to(AgentConfig target) {
        return new HandoffSpec(target, "transfer_to_" + target.getName(), null);
    }

    /** Declares a handoff with a custom tool name and description. */
    public static HandoffSpec of(AgentConfig target, String toolName, String description) {
        return new HandoffSpec(target, toolName, description);
    }

    /** Same as {@link #to(AgentConfig)} with an explicit request-boundary filter. */
    public static HandoffSpec to(AgentConfig target, HandoffInputFilter inputFilter) {
        return to(target).withInputFilter(inputFilter);
    }

    public HandoffSpec withInputFilter(HandoffInputFilter inputFilter) {
        return new HandoffSpec(target, toolName, description, inputFilter);
    }

    String targetName() {
        return target.getName();
    }

    /**
     * Tool schema string, same shape as {@code ToolRegistry#getToolSchemas()}
     * entries. Handoff tools take no parameters: the transfer itself is the
     * whole intent.
     */
    String toolSchema() {
        String safeDescription = description().replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\n"
                + "  \"name\": \"" + toolName + "\",\n"
                + "  \"description\": \"" + safeDescription + "\",\n"
                + "  \"parameters\": {\n"
                + "    \"type\": \"object\",\n"
                + "    \"properties\": {},\n"
                + "    \"required\": []\n"
                + "  }\n"
                + "}";
    }
}
