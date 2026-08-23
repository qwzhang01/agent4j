package io.github.qwzhang01.agent.product;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.product.definition.AgentDefinitionBinder;
import io.github.qwzhang01.agent.product.definition.AgentDefinitionParser;
import io.github.qwzhang01.agent.product.definition.DefinitionException;
import io.github.qwzhang01.agent.product.definition.DefinitionValidator;
import io.github.qwzhang01.agent.product.definition.ValidationError;
import io.github.qwzhang01.agent.product.template.TemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One-time platform scaffolding (Stage 13 M13.1): register implementations,
 * then start every agent defined in a directory of YAML files.
 * <p>
 * This class is the "zero Java per agent" story:
 * <pre>{@code
 * AgentRegistry agents = ProductBootstrapper.builder()
 *         .model("openai", openAiClient)
 *         .model("deepseek", deepSeekClient)
 *         .tool("order-query", orderQueryTool)
 *         .build()
 *         .startAll(Path.of("agents/"));   // business side: YAML files only
 * }</pre>
 * The main method above is written ONCE by the platform team; a business author
 * adds agents by dropping definition files into {@code agents/}.
 * <p>
 * {@link #startAll} is all-or-nothing: every file in the directory must parse and
 * validate, otherwise nothing starts (a half-started platform is a debugging
 * nightmare). All errors across all files are reported in one exception.
 */
public final class ProductBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(ProductBootstrapper.class);

    private final ProductContext context;
    private final TemplateRegistry templates;
    private final AgentDefinitionParser parser = new AgentDefinitionParser();
    private final DefinitionValidator validator = new DefinitionValidator();

    private ProductBootstrapper(ProductContext context, TemplateRegistry templates) {
        this.context = context;
        this.templates = templates;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The registered implementations (for assembly layers that need direct access).
     */
    public ProductContext context() {
        return context;
    }

    /**
     * The registered templates (M13.2). Instantiate produces definitions that
     * then go through the usual validate -&gt; bind -&gt; registry path.
     */
    public TemplateRegistry templates() {
        return templates;
    }

    /**
     * Parse, validate and bind every definition file in {@code agentsDir}
     * (.yaml / .yml / .json). All-or-nothing: any failure means nothing starts.
     *
     * @param agentsDir directory containing agent definition files
     * @return registry of started agents
     * @throws DefinitionException aggregating every parse/validation error
     */
    public AgentRegistry startAll(Path agentsDir) {
        Objects.requireNonNull(agentsDir, "agentsDir must not be null");
        if (!Files.isDirectory(agentsDir)) {
            throw new IllegalArgumentException("agentsDir is not a directory: " + agentsDir);
        }

        List<Path> files = listDefinitionFiles(agentsDir);
        if (files.isEmpty()) {
            log.warn("No definition files found in {}", agentsDir);
            return new AgentRegistry();
        }

        // Phase 1: parse + validate everything, collecting ALL errors.
        List<ValidationError> allErrors = new ArrayList<>();
        List<io.github.qwzhang01.agent.product.definition.AgentDefinition> definitions = new ArrayList<>();
        for (Path file : files) {
            try {
                var definition = parser.parse(file);
                allErrors.addAll(prefix(file, validator.validate(definition, context)));
                definitions.add(definition);
            } catch (DefinitionException e) {
                allErrors.add(new ValidationError(file.getFileName().toString(), e.getMessage()));
            }
        }
        // Cross-file uniqueness: two definitions claiming one name would make
        // registry lookups (and later webhook routing) ambiguous.
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (var definition : definitions) {
            if (!seenNames.add(definition.metadata().name())) {
                allErrors.add(new ValidationError(definition.metadata().name() + " :: metadata.name",
                        "duplicate agent name across definition files"));
            }
        }
        if (!allErrors.isEmpty()) {
            throw new DefinitionException(allErrors); // nothing started (all-or-nothing)
        }

        // Phase 2: everything is clean - bind and register.
        AgentDefinitionBinder binder = new AgentDefinitionBinder(context);
        AgentRegistry registry = new AgentRegistry();
        for (var definition : definitions) {
            Agent agent = binder.bind(definition);
            registry.register(definition.metadata().name(), agent);
            log.info("Started agent '{}' from definition (tenant: {})",
                    definition.metadata().name(), definition.metadata().tenant());
        }
        return registry;
    }

    // --------------------------------------------
    // Helpers
    // --------------------------------------------

    private List<Path> listDefinitionFiles(Path agentsDir) {
        try (Stream<Path> stream = Files.list(agentsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .sorted() // deterministic start order
                    .toList();
        } catch (IOException e) {
            throw new DefinitionException("Cannot list definition files in " + agentsDir, e);
        }
    }

    /**
     * Prefix validation errors with the file they came from (multi-file startup).
     */
    private List<ValidationError> prefix(Path file, List<ValidationError> errors) {
        String fileName = file.getFileName().toString();
        return errors.stream()
                .map(e -> new ValidationError(fileName + " :: " + e.path(), e.message()))
                .toList();
    }

    // ============ Builder ============

    /**
     * Fluent builder for the platform context: implementations in, bootstrapper out.
     */
    public static final class Builder {

        private final ProductContext context = new ProductContext();
        private final TemplateRegistry templates = new TemplateRegistry();

        public Builder model(String name, ModelClient client) {
            context.registerModel(name, client);
            return this;
        }

        public Builder tool(String name, Tool tool) {
            context.registerTool(name, tool);
            return this;
        }

        public Builder contextBuilder(String name, ContextBuilder builder) {
            context.registerContextBuilder(name, builder);
            return this;
        }

        /**
         * Attach the prompt manager (M13.4) - enables persona.promptRef.
         */
        public Builder promptManager(io.github.qwzhang01.agent.product.prompt.PromptManager manager) {
            context.withPromptManager(manager);
            return this;
        }

        /**
         * Register a workflow (M13.5): definitions reference it via
         * spec.workflow, the DAG codec can export it.
         */
        public Builder workflow(String name, io.github.qwzhang01.agent.workflow.Workflow workflow) {
            context.registerWorkflow(name, workflow);
            return this;
        }

        /**
         * Register a per-tenant configuration overlay (M13.5, D7).
         */
        public Builder tenantConfig(io.github.qwzhang01.agent.product.tenant.TenantAgentConfig config) {
            context.registerTenantConfig(config);
            return this;
        }

        /**
         * Load templates from a directory (M13.2). Assembly-time fail-fast:
         * a broken template file throws immediately - the platform does not
         * start half-templated.
         */
        public Builder templateDir(Path dir) {
            templates.loadDir(dir);
            return this;
        }

        public ProductBootstrapper build() {
            return new ProductBootstrapper(context, templates);
        }
    }
}
