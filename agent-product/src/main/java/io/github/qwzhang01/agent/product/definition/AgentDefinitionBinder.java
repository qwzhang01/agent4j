package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.channel.ChannelContext;
import io.github.qwzhang01.agent.channel.SharedAgentSession;
import io.github.qwzhang01.agent.channel.ambient.AmbientInstruction;
import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.ChannelRolePermissions;
import io.github.qwzhang01.agent.channel.identity.IdentityScope;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.FallbackModelClient;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.product.ProductContext;
import io.github.qwzhang01.agent.product.prompt.PromptVersion;
import io.github.qwzhang01.agent.product.tools.HttpApiToolFactory;
import io.github.qwzhang01.agent.product.tenant.TenantAgentConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Translates a validated {@link AgentDefinition} into a running {@link Agent}
 * (Stage 13 M13.1, D2: a translator, not a generator).
 * <p>
 * No code generation, no reflection: every section of the definition is mapped
 * onto the EXISTING Stage 1-12 construction path -
 * <ul>
 *   <li>persona -&gt; {@code AgentConfig.systemPrompt} (+ temperature via decorator)</li>
 *   <li>model -&gt; registered clients, wired as {@code Temperature(Fallback(primary, fallbacks))}</li>
 *   <li>tools -&gt; a fresh {@code InMemoryToolRegistry} holding the referenced SUBSET
 *       (definitions expose a subset of the registry, not the whole toolbox)</li>
 *   <li>memory -&gt; {@code WindowContextBuilder} / named ContextBuilder / null passthrough</li>
 * </ul>
 * A product-layer bug therefore never requires touching the runtime.
 * <p>
 * Contract: the definition MUST have passed {@link DefinitionValidator} first.
 * Binder lookups throw {@link IllegalArgumentException} defensively (they should
 * be unreachable for validated definitions).
 */
public final class AgentDefinitionBinder {

    private final ProductContext context;
    private final HttpApiToolFactory httpToolFactory;

    public AgentDefinitionBinder(ProductContext context) {
        this(context, new HttpApiToolFactory());
    }

    /**
     * @param httpToolFactory builds tools from inline http declarations
     *                        (injectable for tests - env lookup etc.)
     */
    public AgentDefinitionBinder(ProductContext context, HttpApiToolFactory httpToolFactory) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.httpToolFactory = Objects.requireNonNull(httpToolFactory, "httpToolFactory must not be null");
    }

    /**
     * Bind a validated definition into a live agent.
     * <p>
     * Tenant overlay (M13.5, D7): when the definition carries a tenant that has
     * a registered {@link TenantAgentConfig}, the config overlays model /
     * tool subset / prompt channel - tenants can RESTRICT (disable tools) or
     * re-route (canary prompt, cheaper model), never expand.
     */
    public Agent bind(AgentDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        AgentDefinition.Spec spec = definition.spec();
        TenantAgentConfig tenant = tenantOverlay(definition);

        ModelClient modelClient = assembleModelClient(
                modelProvider(spec, tenant),
                spec.model().fallback(),
                spec.persona() != null ? spec.persona().temperature() : null);
        ToolRegistry toolRegistry = assembleToolRegistry(spec.tools(),
                tenant == null ? java.util.Set.of() : tenant.disabledTools());
        ContextBuilder contextBuilder = assembleContextBuilder(spec.memory());

        String systemPrompt = resolveSystemPrompt(definition, tenant);
        AgentConfig config = new AgentConfig(
                definition.metadata().name(), systemPrompt,
                modelClient, toolRegistry, 10, contextBuilder);

        return new SimpleAgent(config);
    }

    /**
     * Bind a definition as a CHANNEL agent (M13.5): same assembly as
     * {@link #bind}, plus declarative ambient instructions built from
     * spec.ambient. Returns the session plus the instructions - wiring the
     * instructions into an AmbientEngine remains assembly-layer work
     * (Stage 12's pattern: the product layer produces, the assembly composes).
     *
     * @param definition validated definition (may carry spec.ambient)
     * @param channel    channel metadata (id + members)
     * @return the bound session and its ambient instructions
     */
    public ChannelBinding bindChannel(AgentDefinition definition, ChannelContext channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        Agent agent = bind(definition);

        // v1 service account: derived from the definition. The default scope
        // intersects with the default "member" role capability; assembly can
        // rebuild with a stricter IdentityScope/role wiring later.
        String tenant = definition.metadata().tenant();
        String accountId = "sa-" + definition.metadata().name() + (tenant == null ? "" : "-" + tenant);
        ServiceAccount account = ServiceAccount.of(accountId,
                new AgentIdentity(definition.metadata().name(),
                        definition.metadata().name(), "platform"),
                IdentityScope.capabilities("member"));

        // Default role permissions: members get a "member" capability set.
        // The assembly layer can rebuild the session with stricter wiring.
        ChannelRolePermissions permissions = (ch, uid) ->
                channel.isMember(uid) ? java.util.Set.of("member") : null;

        SharedAgentSession session = new SharedAgentSession(agent, account, channel,
                permissions, null);
        return new ChannelBinding(session, buildAmbientInstructions(definition));
    }

    /**
     * Session + instructions produced by {@link #bindChannel}.
     */
    public record ChannelBinding(SharedAgentSession session, List<AmbientInstruction> ambient) {
        public ChannelBinding {
            Objects.requireNonNull(session, "session must not be null");
            ambient = ambient == null ? List.of() : List.copyOf(ambient);
        }
    }

    // --------------------------------------------
    // Section assembly
    // --------------------------------------------

    private TenantAgentConfig tenantOverlay(AgentDefinition definition) {
        String tenant = definition.metadata().tenant();
        return tenant == null ? null : context.tenantConfig(tenant).orElse(null);
    }

    private String modelProvider(AgentDefinition.Spec spec, TenantAgentConfig tenant) {
        return tenant != null && tenant.model() != null
                ? tenant.model() : spec.model().provider();
    }

    /**
     * D4 pin, v1 granularity = bind time: a promptRef is resolved ONCE per
     * bind and the CONTENT is snapshotted into the agent instance. A running
     * conversation (one instance) is immune to publishes that happen mid-flight;
     * the next bind picks up whatever resolve returns then. With the product
     * mapping "one conversation = one agent instance" (the channel-layer
     * session), instance-level pin IS conversation-level pin.
     * <p>
     * M13.5: the tenant overlay's promptChannel replaces the DEFINITION's
     * declared channel (operator overrides via PromptManager still win).
     */
    private String resolveSystemPrompt(AgentDefinition definition, TenantAgentConfig tenant) {
        AgentDefinition.Persona persona = definition.spec().persona();
        if (persona == null || persona.promptRef() == null) {
            return persona == null ? null : persona.systemPrompt();
        }
        PromptRef ref = persona.promptRef();
        String declaredChannel = tenant != null && tenant.promptChannel() != null
                ? tenant.promptChannel() : ref.channel();
        PromptVersion version = context.promptManager()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Definition references prompt '" + ref.name()
                                + "' but the platform has no PromptManager"))
                .resolve(ref.name(), definition.metadata().tenant(), declaredChannel)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prompt '" + ref.name() + "' has no version for the routed channel "
                                + "(validate the definition before binding)"));
        return version.content();
    }

    // --------------------------------------------
    // Ambient instructions (M13.5, Stage 12 wiring)
    // --------------------------------------------

    /**
     * Build Stage 12 {@link AmbientInstruction}s from declarative ambient
     * sections. The CONDITION stays a Java extension point: YAML declares
     * when to check (trigger) and what to say (template), never how to judge
     * - v1 default condition is "always worth checking" (payload -&gt; true).
     */
    private List<AmbientInstruction> buildAmbientInstructions(AgentDefinition definition) {
        List<AgentDefinition.AmbientDecl> decls = definition.spec().ambient();
        if (decls == null) {
            return List.of();
        }
        List<AmbientInstruction> instructions = new ArrayList<>();
        for (AgentDefinition.AmbientDecl decl : decls) {
            AgentDefinition.AmbientDecl.TriggerDecl trigger = decl.trigger();
            AmbientInstruction instruction;
            if (trigger.onEvent() != null && !trigger.onEvent().isBlank()) {
                instruction = AmbientInstruction.onEvent(
                        decl.instructionId(), decl.description(), trigger.onEvent(),
                        AmbientInstruction.Importance.valueOf(decl.importance()),
                        payload -> true,
                        payload -> renderTemplate(decl.messageTemplate(), payload));
            } else {
                instruction = AmbientInstruction.scheduled(
                        decl.instructionId(), decl.description(),
                        java.time.Duration.parse(trigger.schedule()),
                        AmbientInstruction.Importance.valueOf(decl.importance()),
                        payload -> true,
                        payload -> renderTemplate(decl.messageTemplate(), payload));
            }
            instructions.add(instruction);
        }
        return instructions;
    }

    private static String renderTemplate(String template, Object payload) {
        com.fasterxml.jackson.databind.JsonNode node =
                payload instanceof com.fasterxml.jackson.databind.JsonNode jsonNode
                        ? jsonNode
                        : new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(payload);
        return io.github.qwzhang01.agent.product.trigger.PayloadRenderer.render(template, node);
    }

    // --------------------------------------------
    // Section assembly
    // --------------------------------------------

    private ModelClient assembleModelClient(String provider, String fallback, Double temperature) {
        ModelClient primary = context.model(provider)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Model '" + provider + "' not registered "
                                + "(validate the definition / tenant config before binding)"));

        ModelClient wired = fallback != null
                ? new FallbackModelClient(primary, context.model(fallback)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Fallback model '" + fallback + "' not registered")))
                : primary;

        // Temperature lives on persona, not on model wiring (sampling belongs to
        // personality). Applied OUTSIDE the fallback chain so both primary and
        // fallback calls carry it.
        return temperature != null ? new TemperatureModelClient(wired, temperature) : wired;
    }

    private ToolRegistry assembleToolRegistry(java.util.List<AgentDefinition.ToolRef> tools,
                                              java.util.Set<String> disabledTools) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        if (tools == null) {
            return registry;
        }
        for (AgentDefinition.ToolRef toolRef : tools) {
            if (disabledTools.contains(toolRef.toolName())) {
                continue; // tenant restriction (D7): restrict, never expand
            }
            Tool tool = toolRef.ref() != null
                    ? context.tool(toolRef.ref())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Tool '" + toolRef.ref() + "' not registered "
                                            + "(validate the definition before binding)"))
                    : httpToolFactory.create(toolRef.http()); // D3: an ordinary Tool
            registry.register(tool);
        }
        return registry;
    }

    private ContextBuilder assembleContextBuilder(AgentDefinition.Memory memory) {
        if (memory == null) {
            return null; // passthrough (Stage 1-7 behavior)
        }
        if (memory.shortTerm() != null) {
            return new WindowContextBuilder(memory.shortTerm().maxMessages());
        }
        if (memory.contextBuilder() != null) {
            return context.contextBuilder(memory.contextBuilder())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Context builder '" + memory.contextBuilder() + "' not registered"));
        }
        return null;
    }
}
