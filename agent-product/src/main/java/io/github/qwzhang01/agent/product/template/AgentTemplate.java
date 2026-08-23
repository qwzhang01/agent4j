package io.github.qwzhang01.agent.product.template;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.qwzhang01.agent.product.definition.AgentDefinition;
import io.github.qwzhang01.agent.product.definition.DefinitionException;
import io.github.qwzhang01.agent.product.definition.ValidationError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A declarative agent template: an {@link AgentDefinition} spec with declared
 * {@code ${variable}} holes (Stage 13 M13.2, D6: instances are fork snapshots).
 * <p>
 * A template is DATA, not code: the spec is kept as a JSON tree and instantiation
 * is a pure tree transformation (substitute placeholders -&gt; rehydrate into an
 * AgentDefinition record). Schema evolution in the definition layer needs no
 * changes here - new spec sections can carry placeholders on day one.
 * <p>
 * D6 fork semantics: {@link #instantiate} produces a complete, independent
 * definition. Upgrading the template later never changes already-created
 * instances; upgrading is an explicit re-instantiate + compare.
 * <p>
 * Template file shape (Kubernetes-style envelope):
 * <pre>{@code
 * apiVersion: v1
 * kind: AgentTemplate
 * metadata:
 *   name: support-agent
 *   version: "1.0"
 *   description: ...
 * variables:
 *   - name: tenantId
 *     required: true
 *   - name: brandName
 *     default: "七七商城"
 * spec:                    # same shape as AgentDefinition.spec, may hold ${var}
 *   persona: ...
 * }</pre>
 *
 * @param apiVersion envelope, must be "v1"
 * @param kind       envelope, must be "AgentTemplate"
 * @param metadata   template identity (name is the registry key)
 * @param variables  declared placeholders; the tree may not reference undeclared ones
 * @param spec       the definition spec tree (with placeholders)
 */
public record AgentTemplate(
        String apiVersion,
        String kind,
        TemplateMetadata metadata,
        List<VariableDecl> variables,
        JsonNode spec) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public AgentTemplate {
        if (!"v1".equals(apiVersion)) {
            throw new IllegalArgumentException(
                    "apiVersion must be 'v1', got " + (apiVersion == null ? "null" : "'" + apiVersion + "'"));
        }
        if (!"AgentTemplate".equals(kind)) {
            throw new IllegalArgumentException(
                    "kind must be 'AgentTemplate', got " + (kind == null ? "null" : "'" + kind + "'"));
        }
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        if (spec.isNull() || spec.isEmpty()) {
            throw new IllegalArgumentException("spec must not be empty");
        }
        variables = variables == null ? List.of() : List.copyOf(variables);

        Set<String> declared = new LinkedHashSet<>();
        for (VariableDecl v : variables) {
            if (!declared.add(v.name())) {
                throw new IllegalArgumentException("duplicate variable '" + v.name() + "'");
            }
        }
        // Every placeholder must be declared - a typo like ${tenantIID} must fail
        // at template load time, not silently stay unreplaced in a live prompt.
        Set<String> used = placeholdersIn(spec);
        for (String used_ : used) {
            if (!declared.contains(used_)) {
                throw new IllegalArgumentException(
                        "placeholder '${" + used_ + "}' is not declared in variables "
                                + "(declared: " + declared + ")");
            }
        }
    }

    // ============ Parsing ============



    /**
     * Parse a template from YAML/JSON text.
     *
     * @throws DefinitionException on syntax errors, unknown fields, invalid structure
     */
    public static AgentTemplate parse(String content) {
        try {
            return MAPPER.readValue(content, AgentTemplate.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new DefinitionException(
                    "Cannot parse agent template: "
                            + (e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getClass().getSimpleName()),
                    e);
        }
    }

    // ============ Instantiation (D6 fork snapshot) ============

    /**
     * Produce a complete, independent agent definition from this template.
     *
     * @param instanceName name for the produced definition (registry key later)
     * @param tenant       optional tenant for the produced definition
     * @param params       variable values; missing required variables (without
     *                     defaults) and undeclared parameter keys are rejected
     * @return a full {@link AgentDefinition} - validate and bind it as usual
     * @throws DefinitionException listing every parameter problem
     */
    public AgentDefinition instantiate(String instanceName, String tenant, Map<String, String> params) {
        if (instanceName == null || instanceName.isBlank()) {
            throw new IllegalArgumentException("instanceName must not be blank");
        }
        Map<String, String> safeParams = params == null ? Map.of() : params;

        // 1. Validate parameters and merge defaults.
        List<ValidationError> errors = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> declared = new HashSet<>();
        for (VariableDecl variable : variables) {
            declared.add(variable.name());
            String provided = safeParams.get(variable.name());
            if (provided != null) {
                values.put(variable.name(), provided);
            } else if (variable.defaultValue() != null) {
                values.put(variable.name(), variable.defaultValue());
            } else if (variable.required()) {
                errors.add(new ValidationError("params." + variable.name(),
                        "required parameter is missing"));
            }
        }
        for (String key : safeParams.keySet()) {
            if (!declared.contains(key)) {
                errors.add(new ValidationError("params." + key,
                        "undeclared parameter (declared: " + declared + ") - typo or stale call?"));
            }
        }
        if (!errors.isEmpty()) {
            throw new DefinitionException(errors);
        }

        // 2. Pure tree transformation: deep-copy the spec, substitute placeholders.
        JsonNode substituted = substitute(spec.deepCopy(), values);

        // 3. Rehydrate into the typed definition (runs the definition's own
        //    envelope/structure checks).
        AgentDefinition.Spec specRecord;
        try {
            specRecord = MAPPER.treeToValue(substituted, AgentDefinition.Spec.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new DefinitionException(
                    "Template spec does not fit the agent definition schema: "
                            + (e.getOriginalMessage() != null ? e.getOriginalMessage()
                            : e.getClass().getSimpleName()),
                    e);
        }
        return new AgentDefinition("v1", "Agent",
                new AgentDefinition.Metadata(instanceName, tenant), specRecord);
    }

    // ============ Placeholder machinery ============

    /**
     * All placeholder names referenced anywhere in the tree.
     */
    static Set<String> placeholdersIn(JsonNode tree) {
        Set<String> found = new LinkedHashSet<>();
        collectPlaceholders(tree, found);
        return found;
    }

    private static void collectPlaceholders(JsonNode node, Set<String> found) {
        if (node instanceof TextNode text) {
            Matcher m = PLACEHOLDER.matcher(text.textValue() == null ? "" : text.textValue());
            while (m.find()) {
                found.add(m.group(1));
            }
        } else if (node instanceof ObjectNode object) {
            object.forEach(child -> collectPlaceholders(child, found));
        } else if (node instanceof ArrayNode array) {
            array.forEach(child -> collectPlaceholders(child, found));
        }
    }

    /**
     * Recursive in-place substitution on a tree the caller already deep-copied.
     */
    private static JsonNode substitute(JsonNode node, Map<String, String> values) {
        if (node instanceof ObjectNode object) {
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String field : fieldNames) {
                object.set(field, substitute(object.get(field), values));
            }
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, substitute(array.get(i), values));
            }
            return array;
        }
        if (node instanceof TextNode text) {
            String raw = text.textValue() == null ? "" : text.textValue();
            Matcher m = PLACEHOLDER.matcher(raw);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String replacement = values.getOrDefault(m.group(1), m.group(0));
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            return TextNode.valueOf(sb.toString());
        }
        return node; // numbers, booleans, nulls carry no placeholders
    }
}
