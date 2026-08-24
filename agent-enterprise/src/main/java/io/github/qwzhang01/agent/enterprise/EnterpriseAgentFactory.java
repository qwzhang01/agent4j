package io.github.qwzhang01.agent.enterprise;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.enterprise.govern.RoleBasedPermissionChecker;
import io.github.qwzhang01.agent.enterprise.govern.EnterpriseAuditTrail;
import io.github.qwzhang01.agent.enterprise.knowledge.KnowledgeBase;
import io.github.qwzhang01.agent.enterprise.knowledge.KnowledgeTool;
import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.memory.MemoryContextBuilder;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.security.AuditLogger;
import io.github.qwzhang01.agent.security.GovernedToolExecutor;
import io.github.qwzhang01.agent.security.ToolApprovalService;
import io.github.qwzhang01.agent.security.ToolPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request-scoped assembly of the enterprise execution chain (Stage 15 M15.5,
 * blueprint D2).
 * <p>
 * One factory per application holds the SHARED, stateless parts (model
 * client, business tools, role matrix, audit trail). Each call to
 * {@link #forRequest} clones the REQUEST-SCOPED parts around one
 * {@link RequestContext}: the role-aware permission checker bound to the
 * user's roles, the audit logger that attributes every governance event to
 * this tenant/user, the knowledge tool bound to this tenant, the memory
 * injection limited to this request's scope whitelist, and a usage tracker
 * the assistant reads after the run to bill the ledger.
 * <p>
 * Why explicit cloning instead of ThreadLocal: identity must be trackable on
 * the call stack ("why was this denied" has an answer in the code path, not
 * in invisible thread state). The cost is a handful of small objects per
 * request - negligible next to one LLM call.
 */
public final class EnterpriseAgentFactory {

    private final ModelClient modelClient;
    private final List<Tool> sharedTools;
    private final KnowledgeBase knowledgeBase;
    private final MemoryStore memoryStore;
    private final Map<String, Set<String>> roleMatrix;
    private final ToolPolicy toolPolicy;
    private final EnterpriseAuditTrail auditTrail;
    private final ToolApprovalService toolApprovalService;
    private final String agentName;
    private final String systemPrompt;
    private final int maxSteps;

    private EnterpriseAgentFactory(Builder builder) {
        this.modelClient = builder.modelClient;
        this.sharedTools = List.copyOf(builder.tools);
        this.knowledgeBase = builder.knowledgeBase;
        this.memoryStore = builder.memoryStore;
        this.roleMatrix = builder.roleMatrix;
        this.toolPolicy = builder.toolPolicy;
        this.auditTrail = builder.auditTrail;
        this.toolApprovalService = builder.toolApprovalService;
        this.agentName = builder.agentName;
        this.systemPrompt = builder.systemPrompt;
        this.maxSteps = builder.maxSteps;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ============ Request-Scoped Assembly ============

    /**
     * Assemble the execution chain bound to one request context.
     *
     * @param ctx the authenticated request context (roles, tenant, scopes)
     * @return a request-scoped agent plus its usage tracker
     */
    public EnterpriseAgent forRequest(RequestContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");

        // 1. per-request registry: shared business tools + the tenant-bound
        //    knowledge tool (the model can never point it at another tenant)
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        for (Tool tool : sharedTools) {
            registry.register(tool);
        }
        if (knowledgeBase != null) {
            registry.register(KnowledgeTool.forTenant(knowledgeBase, ctx.tenantId()));
        }

        // 2. per-request usage tracking (bills the CostLedger after the run)
        UsageTracker tracker = new UsageTracker();
        ModelClient tracked = new TrackingModelClient(modelClient, tracker);

        // 3. per-request governance wiring
        RoleBasedPermissionChecker checker = RoleBasedPermissionChecker.forRequest(
                roleMatrix, toolPolicy, ctx.user().roles());
        AuditLogger requestAudit = auditTrail != null
                ? auditTrail.forRequest(ctx, agentName)
                : null;

        GovernedToolExecutor executor = GovernedToolExecutor
                .builder(new DefaultToolExecutor(registry))
                .permissionChecker(checker)
                .auditLogger(requestAudit)
                .approvalService(toolApprovalService)
                .build();

        // 4. per-request memory injection (scope whitelist = tenant + user)
        MemoryContextBuilder contextBuilder = memoryStore != null
                ? new MemoryContextBuilder(
                        new MemoryRetriever(memoryStore), ctx.memoryScopes(),
                        null, null, null, 0)
                : null;

        AgentConfig config = new AgentConfig(
                agentName, systemPrompt, tracked, registry, maxSteps, contextBuilder);
        return new EnterpriseAgent(
                new SimpleAgent(config, new ReActAgentLoop(executor)), tracker);
    }

    // ============ Shared Accessors ============

    /**
     * The shared audit trail (never null - the builder defaults to a fresh
     * one; every request-scoped logger feeds the same ledger).
     */
    public EnterpriseAuditTrail sharedAuditTrail() {
        return auditTrail;
    }

    // ============ Request-Scoped Agent ============

    /**
     * The execution chain of one request: the underlying Agent plus the
     * token usage it accumulated (read after the run to bill the ledger).
     */
    public static final class EnterpriseAgent {

        private final Agent agent;
        private final UsageTracker tracker;

        private EnterpriseAgent(Agent agent, UsageTracker tracker) {
            this.agent = agent;
            this.tracker = tracker;
        }

        /**
         * One-shot execution (same contract as {@link Agent#run(String)}).
         */
        public String run(String userInput) {
            return agent.run(userInput);
        }

        /**
         * Prompt tokens accumulated by this request so far.
         */
        public long promptTokens() {
            return tracker.prompt.get();
        }

        /**
         * Completion tokens accumulated by this request so far.
         */
        public long completionTokens() {
            return tracker.completion.get();
        }

        /**
         * The underlying agent (for callers needing the full Agent contract).
         */
        public Agent unwrap() {
            return agent;
        }
    }

    // ============ Internal Helpers ============

    /** Per-request token accumulator. */
    static final class UsageTracker {
        final AtomicLong prompt = new AtomicLong();
        final AtomicLong completion = new AtomicLong();

        void add(int promptTokens, int completionTokens) {
            prompt.addAndGet(promptTokens);
            completion.addAndGet(completionTokens);
        }
    }

    /** Decorator that bills every model response into the tracker. */
    static final class TrackingModelClient implements ModelClient {

        private final ModelClient delegate;
        private final UsageTracker tracker;

        TrackingModelClient(ModelClient delegate, UsageTracker tracker) {
            this.delegate = delegate;
            this.tracker = tracker;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            ModelResponse response = delegate.chat(request);
            ModelResponse.TokenUsage usage = response.usage();
            if (usage != null) {
                tracker.add(usage.promptTokens(), usage.completionTokens());
            }
            return response;
        }

        @Override
        public java.util.stream.Stream<io.github.qwzhang01.agent.core.model.StreamEvent> stream(
                ModelRequest request) {
            // v1: the enterprise path is synchronous chat only; streaming is
            // delegated unchanged (usage tracking covers chat responses)
            return delegate.stream(request);
        }
    }

    // ============ Builder ============

    /**
     * Builder producing the {@link EnterpriseAssistant} facade. Required:
     * {@code modelClient}. Everything else has a sensible default.
     */
    public static final class Builder {

        private ModelClient modelClient;
        private final List<Tool> tools = new ArrayList<>();
        private KnowledgeBase knowledgeBase;
        private MemoryStore memoryStore;
        private Map<String, Set<String>> roleMatrix = Map.of();
        private ToolPolicy toolPolicy = new ToolPolicy(
                io.github.qwzhang01.agent.security.ToolPermission.AUTO);
        private EnterpriseAuditTrail auditTrail = new EnterpriseAuditTrail();
        private ToolApprovalService toolApprovalService;
        private String agentName = "enterprise-assistant";
        private String systemPrompt = """
                You are the enterprise assistant. Answer factual questions about company \
                rules and products by calling search_knowledge first, then cite the \
                retrieved titles. For business operations, call the matching tool.""";
        private int maxSteps = 10;
        private io.github.qwzhang01.agent.enterprise.govern.CostLedger costLedger;
        private io.github.qwzhang01.agent.enterprise.task.EnterpriseTaskManager taskManager;

        private Builder() {
        }

        public Builder modelClient(ModelClient modelClient) {
            this.modelClient = Objects.requireNonNull(modelClient);
            return this;
        }

        /** Register a stateless shared business tool (e.g. order query). */
        public Builder tool(Tool tool) {
            this.tools.add(Objects.requireNonNull(tool));
            return this;
        }

        /** Tenant knowledge base; enables the search_knowledge tool per request. */
        public Builder knowledgeBase(KnowledgeBase knowledgeBase) {
            this.knowledgeBase = knowledgeBase;
            return this;
        }

        /** Memory store; enables tenant+user memory injection into the context. */
        public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = memoryStore;
            return this;
        }

        /** Role -> granted tool names (the permission matrix). */
        public Builder roleMatrix(Map<String, Set<String>> roleMatrix) {
            this.roleMatrix = Objects.requireNonNull(roleMatrix);
            return this;
        }

        /** Fallback policy for tools not granted by any role. */
        public Builder toolPolicy(ToolPolicy toolPolicy) {
            this.toolPolicy = Objects.requireNonNull(toolPolicy);
            return this;
        }

        /** Shared audit trail (attribution ledger); defaults to a fresh one. */
        public Builder auditTrail(EnterpriseAuditTrail auditTrail) {
            this.auditTrail = Objects.requireNonNull(auditTrail);
            return this;
        }

        /** Tool-level approval backend for REQUIRES_APPROVAL tools. */
        public Builder toolApprovalService(ToolApprovalService toolApprovalService) {
            this.toolApprovalService = toolApprovalService;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = Objects.requireNonNull(agentName);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = Objects.requireNonNull(systemPrompt);
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            if (maxSteps <= 0) {
                throw new IllegalArgumentException("maxSteps must be positive");
            }
            this.maxSteps = maxSteps;
            return this;
        }

        /** Cost ledger; enables the pre-gate and post-recording around ask. */
        public Builder costLedger(io.github.qwzhang01.agent.enterprise.govern.CostLedger costLedger) {
            this.costLedger = costLedger;
            return this;
        }

        /** Task manager; enables submitTask/approve/reject on the facade. */
        public Builder taskManager(io.github.qwzhang01.agent.enterprise.task.EnterpriseTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        /**
         * Build the {@link EnterpriseAssistant} facade.
         */
        public EnterpriseAssistant build() {
            if (modelClient == null) {
                throw new IllegalStateException("modelClient is required");
            }
            return new EnterpriseAssistant(
                    new EnterpriseAgentFactory(this), costLedger, taskManager);
        }
    }
}
