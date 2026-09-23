package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.GuardrailChain;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.core.tool.contract.ContractAwareToolExecutor;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Secure-by-default agent assembly (Stage 2.4, harness roadmap).
 * <p>
 * The contract flips: business code calling this builder gets the full
 * governance stack wired by default — no hand-assembled eight-layer
 * decorator sandwich. What was opt-in in 0.1.3 becomes the default here;
 * the raw path still exists but must be asked for by name
 * ({@link UnsafeAgentBuilder}), so "ungoverned" can never be silent.
 * <p>
 * Default stack (order per the Stage 2.5 order contract — validation first):
 * <pre>
 *   Agent
 *     └─ AgentConfig
 *          ├─ guardrails: input + output guardrail chain (if configured)
 *          └─ toolExecutor: ContractAwareToolExecutor       // outermost: schema + timeout + no-ctx refuse
 *               └─ GovernedToolExecutor                     // permission → rate-limit → approval → sanitize → audit
 *                    └─ DefaultToolExecutor                 // actual lookup + execution
 * </pre>
 * Side-effect tools without an approval service are denied (deny-on-absence).
 * Call {@link #approvalService} to plug in a human / durable approver.
 * Default permissions derived from the Tool Contract (Stage 2.1):
 * read-shaped tools (NONE / READ_ONLY) run automatic; everything else —
 * SIDE_EFFECT, DESTRUCTIVE and the honest UNKNOWN — requires approval.
 * Unknown tools are refused, never string-returned.
 * <p>
 * Runtime profile: every assembly logs its profile (SECURE / UNSAFE) at
 * build time — the profile line is greppable in incident triage.
 */
public class SecureAgentBuilder {

    private static final Logger log = LoggerFactory.getLogger(SecureAgentBuilder.class);

    private final String name;
    private final io.github.qwzhang01.agent.core.client.ModelClient modelClient;
    private final ToolRegistry toolRegistry;

    private String systemPrompt;
    private int maxSteps = 25;
    private io.github.qwzhang01.agent.core.agent.ContextBuilder contextBuilder;
    private GuardrailChain guardrails;
    private ToolApprovalService approvalService = ConsoleApprovalService.autoReject();
    private ResultSanitizer resultSanitizer = new DefaultResultSanitizer();
    private AuditLogger auditLogger = new InMemoryAuditLogger();
    private RateLimiter rateLimiter;
    private io.github.qwzhang01.agent.core.agent.AgentLoop agentLoop;

    private SecureAgentBuilder(String name,
                               io.github.qwzhang01.agent.core.client.ModelClient modelClient,
                               ToolRegistry toolRegistry) {
        this.name = name;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
    }

    public static SecureAgentBuilder secure(String name,
                                            io.github.qwzhang01.agent.core.client.ModelClient modelClient,
                                            ToolRegistry toolRegistry) {
        log.info("[RuntimeProfile] SECURE agent '{}' assembling: governed executor, " +
                "validation-first chain, unknown tools refused, " +
                "side-effect tools require approval (deny-on-absence)", name);
        return new SecureAgentBuilder(name, modelClient, toolRegistry);
    }

    public SecureAgentBuilder systemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public SecureAgentBuilder maxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    public SecureAgentBuilder contextBuilder(io.github.qwzhang01.agent.core.agent.ContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
        return this;
    }

    public SecureAgentBuilder guardrails(GuardrailChain guardrails) {
        this.guardrails = guardrails;
        return this;
    }

    /** Null disables interactive approval — side-effect tools are then denied. */
    public SecureAgentBuilder approvalService(ToolApprovalService approvalService) {
        this.approvalService = approvalService;
        return this;
    }

    public SecureAgentBuilder resultSanitizer(ResultSanitizer resultSanitizer) {
        this.resultSanitizer = resultSanitizer;
        return this;
    }

    public SecureAgentBuilder auditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
        return this;
    }

    public SecureAgentBuilder rateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        return this;
    }

    public SecureAgentBuilder agentLoop(io.github.qwzhang01.agent.core.agent.AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
        return this;
    }

    /** Build the governed AgentConfig. */
    public AgentConfig buildConfig() {
        GovernedToolExecutor governed = GovernedToolExecutor
                .builder(new DefaultToolExecutor(toolRegistry))
                .permissionChecker(new PermissionChecker(contractDerivedPolicy()))
                .approvalService(approvalService)
                .resultSanitizer(resultSanitizer)
                .auditLogger(auditLogger)
                .rateLimiter(rateLimiter)
                .build();
        // Validation sits OUTSIDE governance: malformed calls never consume
        // a permission check, a rate-limit token, or a human decision.
        io.github.qwzhang01.agent.core.tool.ToolExecutor stack =
                new ContractAwareToolExecutor(toolRegistry, governed);
        return new AgentConfig(name, systemPrompt, modelClient, toolRegistry,
                maxSteps, contextBuilder, java.util.List.of(),
                guardrails == null ? GuardrailChain.none() : guardrails, stack);
    }

    public Agent build() {
        AgentConfig config = buildConfig();
        if (agentLoop != null) {
            return new SimpleAgent(config, agentLoop);
        }
        return new SimpleAgent(config);
    }

    /**
     * Read-shaped tools (NONE / READ_ONLY) default to AUTO; SIDE_EFFECT,
     * DESTRUCTIVE and UNKNOWN default to REQUIRES_APPROVAL — the
     * conservative mapping the contract promises.
     */
    private ToolPolicy contractDerivedPolicy() {
        ToolPolicy policy = new ToolPolicy(ToolPermission.REQUIRES_APPROVAL);
        for (io.github.qwzhang01.agent.core.tool.Tool tool : toolRegistry.listTools()) {
            ToolPermission permission = tool.definition().sideEffectLevel().isReadShaped()
                    ? ToolPermission.AUTO
                    : ToolPermission.REQUIRES_APPROVAL;
            policy.setPermission(tool.getName(), permission);
        }
        return policy;
    }

}
