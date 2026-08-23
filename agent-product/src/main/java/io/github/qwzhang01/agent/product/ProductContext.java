package io.github.qwzhang01.agent.product;

import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.product.prompt.PromptManager;
import io.github.qwzhang01.agent.product.tenant.TenantAgentConfig;
import io.github.qwzhang01.agent.workflow.Workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The product-layer registry: "names -&gt; implementations" (Stage 13 M13.1, the
 * other end of D1's reference indirection).
 * <p>
 * AgentDefinition files hold NAMES; this context holds the IMPLEMENTATIONS those
 * names resolve to - registered model clients, tools, context builders and the
 * prompt manager. The platform team registers implementations once (Java,
 * one-time scaffolding); business authors then reference them from YAML
 * indefinitely.
 * <p>
 * Duplicate registration fails fast: silently overwriting a name that live
 * definitions point to would change agent behavior without any definition change.
 */
public final class ProductContext {

    private final Map<String, ModelClient> models = new LinkedHashMap<>();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, ContextBuilder> contextBuilders = new LinkedHashMap<>();
    private final Map<String, Workflow> workflows = new LinkedHashMap<>();
    private final Map<String, TenantAgentConfig> tenantConfigs = new LinkedHashMap<>();
    private final Map<String, ServiceAccount> serviceAccounts = new LinkedHashMap<>();
    private PromptManager promptManager;

    // ============ Registration ============

    /**
     * Register a model client under a name (e.g. "openai", "deepseek").
     */
    public ProductContext registerModel(String name, ModelClient client) {
        requireName(name, "model");
        Objects.requireNonNull(client, "client must not be null");
        if (models.containsKey(name)) {
            throw new IllegalArgumentException("Model '" + name + "' is already registered");
        }
        models.put(name, client);
        return this;
    }

    /**
     * Register a tool under an explicit name (decoupled from {@link Tool#getName()}).
     */
    public ProductContext registerTool(String name, Tool tool) {
        requireName(name, "tool");
        Objects.requireNonNull(tool, "tool must not be null");
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("Tool '" + name + "' is already registered");
        }
        tools.put(name, tool);
        return this;
    }

    /**
     * Register a context builder (rich memory strategies stay in Java, D1).
     */
    public ProductContext registerContextBuilder(String name, ContextBuilder builder) {
        requireName(name, "context builder");
        Objects.requireNonNull(builder, "builder must not be null");
        if (contextBuilders.containsKey(name)) {
            throw new IllegalArgumentException("Context builder '" + name + "' is already registered");
        }
        contextBuilders.put(name, builder);
        return this;
    }

    /**
     * Attach the prompt manager (M13.4). Single instance; fail fast on a second
     * attach for the same reason as duplicate names.
     */
    public ProductContext withPromptManager(PromptManager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (promptManager != null) {
            throw new IllegalArgumentException("A PromptManager is already attached");
        }
        this.promptManager = manager;
        return this;
    }

    /**
     * Register a workflow (M13.5): definitions reference it via spec.workflow,
     * the DAG codec exports it.
     */
    public ProductContext registerWorkflow(String name, Workflow workflow) {
        requireName(name, "workflow");
        Objects.requireNonNull(workflow, "workflow must not be null");
        if (workflows.containsKey(name)) {
            throw new IllegalArgumentException("Workflow '" + name + "' is already registered");
        }
        workflows.put(name, workflow);
        return this;
    }

    /**
     * Register a per-tenant configuration overlay (M13.5, D7).
     */
    public ProductContext registerTenantConfig(TenantAgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (tenantConfigs.containsKey(config.tenantId())) {
            throw new IllegalArgumentException(
                    "Tenant config for '" + config.tenantId() + "' is already registered");
        }
        tenantConfigs.put(config.tenantId(), config);
        return this;
    }

    /**
     * Register a provisioned service account under a name (D7): a tenant
     * config's {@code serviceAccount} field holds this NAME, the account
     * itself (scope / validity window / identity) is provisioned here by an
     * admin - the same names-in-definition pattern as models and tools.
     */
    public ProductContext registerServiceAccount(String name, ServiceAccount account) {
        requireName(name, "service account");
        Objects.requireNonNull(account, "account must not be null");
        if (serviceAccounts.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Service account '" + name + "' is already registered");
        }
        serviceAccounts.put(name, account);
        return this;
    }

    // ============ Lookup ============

    public Optional<ModelClient> model(String name) {
        return Optional.ofNullable(models.get(name));
    }

    public Optional<Tool> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Optional<ContextBuilder> contextBuilder(String name) {
        return Optional.ofNullable(contextBuilders.get(name));
    }

    /**
     * The prompt manager (M13.4); empty when the platform runs without
     * prompt-as-asset (all definitions then use inline systemPrompt).
     */
    public Optional<PromptManager> promptManager() {
        return Optional.ofNullable(promptManager);
    }

    public Optional<Workflow> workflow(String name) {
        return Optional.ofNullable(workflows.get(name));
    }

    public List<String> workflowNames() {
        return List.copyOf(workflows.keySet());
    }

    /**
     * The tenant overlay for a tenant id (M13.5, D7).
     */
    public Optional<TenantAgentConfig> tenantConfig(String tenantId) {
        return Optional.ofNullable(tenantConfigs.get(tenantId));
    }

    /**
     * A provisioned service account by name (D7); empty when not registered.
     */
    public Optional<ServiceAccount> serviceAccount(String name) {
        return Optional.ofNullable(serviceAccounts.get(name));
    }

    // ============ Name listings (validation error messages) ============

    public List<String> modelNames() {
        return List.copyOf(models.keySet());
    }

    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    public List<String> contextBuilderNames() {
        return List.copyOf(contextBuilders.keySet());
    }

    public List<String> serviceAccountNames() {
        return List.copyOf(serviceAccounts.keySet());
    }

    // --------------------------------------------
    // Internals
    // --------------------------------------------

    private static void requireName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(what + " name must not be blank");
        }
    }
}
