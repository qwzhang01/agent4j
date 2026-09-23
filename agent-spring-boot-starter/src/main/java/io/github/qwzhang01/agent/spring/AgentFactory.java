package io.github.qwzhang01.agent.spring;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.security.ToolApprovalService;
import io.github.qwzhang01.agent.security.SecureAgentBuilder;

import java.util.Objects;

/**
 * Creates {@link Agent} instances from the shared {@link ModelClient} bean.
 * <p>
 *  the factory is now profile-aware.
 * <ul>
 *   <li><b>SECURE</b> — every agent goes through {@link SecureAgentBuilder}:
 *       governed tool executor, contract-derived permissions (read-shaped
 *       auto / side-effect approval), validation-first chain, unknown tools
 *       refused. Side-effect tools without an approval service are
 *       <em>denied</em> (deny-on-absence), not auto-approved.</li>
 *   <li><b>TEST</b> — same governed stack, but auto-approval is on:
 *       non-interactive test runs don't hang waiting for a human.</li>
 *   <li><b>UNSAFE</b> — the legacy raw path ({@code SimpleAgent} directly),
 *       chosen by name. Logs its profile loudly at assembly.</li>
 * </ul>
 * Intentionally not a singleton {@code Agent} bean: applications such as
 * Moonlit have per-character system prompts and should call
 * {@link #create(String, String)} for each character.
 */
public class AgentFactory {

    static final int DEFAULT_MAX_STEPS = 10;

    private final ModelClient modelClient;
    private final AgentProfile profile;
    private final ToolApprovalService approvalService;

    /** Legacy 1-arg constructor: profile SECURE, deny-on-absence approval. */
    public AgentFactory(ModelClient modelClient) {
        this(modelClient, AgentProfile.SECURE, null);
    }

    /**
     * Profile-aware constructor. A {@code null} approvalService under
     * SECURE means side-effect tools are denied; under TEST it means
     * auto-approve.
     */
    public AgentFactory(ModelClient modelClient, AgentProfile profile,
                        ToolApprovalService approvalService) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.profile = profile == null ? AgentProfile.SECURE : profile;
        this.approvalService = approvalService;
    }

    /** The profile this factory assembles under (for checks/reporting). */
    public AgentProfile profile() {
        return profile;
    }

    /** The approval service in effect (null = profile default applies). */
    public ToolApprovalService approvalService() {
        return approvalService;
    }

    /**
     * Create an agent with an empty tool registry and default max steps ({@value DEFAULT_MAX_STEPS}).
     *
     * @param name agent name (used in config / observability)
     * @param systemPrompt per-character system prompt
     */
    public Agent create(String name, String systemPrompt) {
        return create(name, systemPrompt, new InMemoryToolRegistry(), DEFAULT_MAX_STEPS);
    }

    /**
     * Create an agent with an explicit tool registry and step bound.
     *
     * @param name agent name
     * @param systemPrompt per-character system prompt
     * @param tools tools available to this agent; {@code null} becomes an empty in-memory registry
     * @param maxSteps safety bound against unbounded tool loops
     */
    public Agent create(String name, String systemPrompt, ToolRegistry tools, int maxSteps) {
        ToolRegistry registry = tools != null ? tools : new InMemoryToolRegistry();
        return switch (profile) {
            case SECURE, TEST -> createGoverned(name, systemPrompt, registry, maxSteps);
            case UNSAFE -> createRaw(name, systemPrompt, registry, maxSteps);
        };
    }

    private Agent createGoverned(String name, String systemPrompt,
                                 ToolRegistry registry, int maxSteps) {
        SecureAgentBuilder builder = SecureAgentBuilder.secure(name, modelClient, registry)
                .maxSteps(maxSteps)
                .systemPrompt(systemPrompt);
        // Approval: explicit service wins; otherwise the profile default —
        // SECURE denies on absence (null approval service = side-effect
        // tools denied per SecureAgentBuilder's null contract), TEST
        // auto-approves (non-interactive).
        ToolApprovalService approval = approvalService;
        if (approval == null && profile == AgentProfile.TEST) {
            approval = io.github.qwzhang01.agent.security.ConsoleApprovalService.autoApprove();
        }
        if (approval == null && profile == AgentProfile.SECURE) {
            // deny-on-absence: SecureAgentBuilder treats a null service as
            // "side-effect tools denied" — exactly the secure default; pass
            // an explicit rejecting service so the intent is greppable.
            approval = io.github.qwzhang01.agent.security.ConsoleApprovalService.autoReject();
        }
        return builder.approvalService(approval).build();
    }

    private Agent createRaw(String name, String systemPrompt,
                            ToolRegistry registry, int maxSteps) {
        org.slf4j.LoggerFactory.getLogger(AgentFactory.class)
                .warn("[RuntimeProfile] UNSAFE agent '{}' assembled: NO governance, NO approval, "
                        + "NO validation chain - raw executor, chosen by profile=unsafe", name);
        AgentConfig config = new AgentConfig(name, systemPrompt, modelClient, registry, maxSteps);
        return new SimpleAgent(config);
    }
}
