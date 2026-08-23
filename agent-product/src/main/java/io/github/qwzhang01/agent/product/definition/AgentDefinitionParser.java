package io.github.qwzhang01.agent.product.definition;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Parses YAML (and JSON - YAML is a superset of JSON, one mapper handles both)
 * into {@link AgentDefinition} (Stage 13 M13.1).
 * <p>
 * Fail-fast with location information (D8): syntax errors carry line/column,
 * unknown fields are rejected (a typo like {@code systemprompt} must not be
 * silently ignored). Fields that belong to later milestones produce a targeted
 * "planned in M13.x" message instead of a bare unknown-property error.
 */
public final class AgentDefinitionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /**
     * Fields accepted by the full Stage 13 blueprint but not yet implemented.
     * Mapping: field name -&gt; human hint. Keep in sync with milestone progress.
     */
    private static final Map<String, String> PLANNED_FIELDS = Map.of(
            "longTerm", "spec.memory.longTerm is planned for a later milestone; "
                    + "wire a named spec.memory.contextBuilder instead",
            "template", "template-derived definitions (template + overrides) are v2; "
                    + "produce a full definition via TemplateRegistry.instantiate instead"
    );

    /**
     * Parse a definition file (.yaml / .yml / .json - all via the YAML mapper).
     *
     * @param file definition file
     * @return parsed definition (NOT yet validated - run {@link DefinitionValidator})
     * @throws DefinitionException with file name + line/column on parse failure
     */
    public AgentDefinition parse(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DefinitionException("Cannot read definition file " + file, e);
        }
        try {
            return parse(content);
        } catch (DefinitionException e) {
            // Re-wrap so the message names the offending file.
            throw new DefinitionException(file + ": " + e.getMessage(), e.getCause());
        }
    }

    /**
     * Parse definition content (YAML or JSON text).
     *
     * @param content definition text
     * @return parsed definition (NOT yet validated)
     * @throws DefinitionException with line/column on syntax errors, unknown fields
     */
    public AgentDefinition parse(String content) {
        try {
            return MAPPER.readValue(content, AgentDefinition.class);
        } catch (UnrecognizedPropertyException e) {
            String hint = PLANNED_FIELDS.get(e.getPropertyName());
            String location = location(e);
            if (hint != null) {
                throw new DefinitionException(
                        "Unknown field '" + e.getPropertyName() + "'" + location + ": " + hint, e);
            }
            throw new DefinitionException(
                    "Unknown field '" + e.getPropertyName() + "'" + location
                            + ". Remove it or check the spelling against the schema "
                            + "(known: apiVersion, kind, metadata, spec)", e);
        } catch (JacksonException e) {
            throw new DefinitionException(
                    "Cannot parse definition" + location(e) + ": " + shortMessage(e), e);
        }
    }

    // --------------------------------------------
    // Helpers
    // --------------------------------------------

    private static String location(JacksonException e) {
        var loc = e.getLocation();
        if (loc == null) {
            return "";
        }
        return " (line " + loc.getLineNr() + ", column " + loc.getColumnNr() + ")";
    }

    private static String shortMessage(JacksonException e) {
        String msg = e.getOriginalMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }
}
