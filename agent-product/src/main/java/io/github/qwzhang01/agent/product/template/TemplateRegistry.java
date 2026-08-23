package io.github.qwzhang01.agent.product.template;

import io.github.qwzhang01.agent.product.definition.AgentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of {@link AgentTemplate}s (Stage 13 M13.2).
 * <p>
 * Registration discipline follows {@code ProductContext}: duplicate names fail
 * fast. Upgrading a template is an EXPLICIT {@link #replace(AgentTemplate)} -
 * never a silent overwrite, because D6 promises that live instances are fork
 * snapshots (a silent overwrite would only confuse the next instantiate, but
 * the discipline keeps audits honest about which version produced what).
 * <p>
 * v1 indexes by name only; version selection ({@code name@version}) is v2.
 */
public final class TemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(TemplateRegistry.class);

    /** Built-in templates shipped on the classpath. */
    private static final List<String> BUILTIN_RESOURCES = List.of(
            "/templates/support-agent.yaml",
            "/templates/knowledge-assistant.yaml"
    );

    private final Map<String, AgentTemplate> templates = new LinkedHashMap<>();

    // ============ Built-ins ============

    /**
     * A registry pre-loaded with the built-in templates
     * (support-agent + knowledge-assistant).
     */
    public static TemplateRegistry builtins() {
        TemplateRegistry registry = new TemplateRegistry();
        for (String resource : BUILTIN_RESOURCES) {
            registry.register(loadResource(resource));
        }
        return registry;
    }

    // ============ Registration ============

    /**
     * Register a template under its metadata name.
     *
     * @throws IllegalArgumentException on duplicate name
     */
    public TemplateRegistry register(AgentTemplate template) {
        requireTemplate(template);
        if (templates.containsKey(template.metadata().name())) {
            throw new IllegalArgumentException(
                    "Template '" + template.metadata().name() + "' is already registered "
                            + "(upgrading is an explicit replace())");
        }
        templates.put(template.metadata().name(), template);
        return this;
    }

    /**
     * Explicitly upgrade a registered template (D6: existing instances are
     * unaffected; only future instantiations use the new version).
     *
     * @throws IllegalArgumentException if the template is not registered
     */
    public TemplateRegistry replace(AgentTemplate template) {
        requireTemplate(template);
        String name = template.metadata().name();
        if (!templates.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Template '" + name + "' is not registered (register() first)");
        }
        templates.put(name, template);
        log.info("Template '{}' upgraded to version {} (existing instances unaffected)",
                name, template.metadata().version());
        return this;
    }

    // ============ Lookup ============

    public Optional<AgentTemplate> get(String name) {
        return Optional.ofNullable(templates.get(name));
    }

    public Set<String> names() {
        return Set.copyOf(templates.keySet());
    }

    // ============ Instantiation ============

    /**
     * Look up a template and instantiate it (delegates parameter validation
     * and substitution to the template itself).
     *
     * @throws IllegalArgumentException if the template name is unknown
     * @throws io.github.qwzhang01.agent.product.definition.DefinitionException on parameter problems
     */
    public AgentDefinition instantiate(String templateName, String instanceName,
                                       String tenant, Map<String, String> params) {
        AgentTemplate template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException(
                    "Template '" + templateName + "' is not registered, available: " + templates.keySet());
        }
        return template.instantiate(instanceName, tenant, params);
    }

    // ============ File loading ============

    /**
     * Load every template file from a directory (.yaml / .yml / .json).
     *
     * @throws UncheckedIOException   on IO failure
     * @throws io.github.qwzhang01.agent.product.definition.DefinitionException on parse failure
     */
    public TemplateRegistry loadDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("template dir is not a directory: " + dir);
        }
        try (var files = Files.list(dir)) {
            List<Path> sorted = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .sorted()
                    .toList();
            for (Path file : sorted) {
                register(AgentTemplate.parse(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load templates from " + dir, e);
        }
        return this;
    }

    private static AgentTemplate loadResource(String resource) {
        try (InputStream in = TemplateRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Built-in template not found on classpath: " + resource);
            }
            return AgentTemplate.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read built-in template " + resource, e);
        }
    }

    private static void requireTemplate(AgentTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
    }
}
