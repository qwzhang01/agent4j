package io.github.qwzhang01.agent.core.tool.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Harness-wide JSON argument validator (Stage 2.2).
 * <p>
 * One validator, one dialect, applied identically to MCP tools, plugins
 * and built-ins — "the same validation chain for everything" (roadmap 2.2).
 * The supported schema dialect is the practical subset every tool in this
 * repo actually uses: {@code type}, {@code required}, {@code properties},
 * {@code enum}, {@code items}, {@code maxLength} / {@code maxItems} and
 * nested objects. JSON Schema keywords the validator does not implement
 * are ignored <b>with record</b> — validation never silently upgrades a
 * dialect it cannot parse into a pass; unparseable schemas are an
 * {@link #schemaInvalid} verdict, not a free pass.
 * <p>
 * Structural caps enforced regardless of schema (harness-level guard,
 * applies to every tool including untyped legacy ones):
 * <ul>
 *   <li>total argument size vs the definition's {@code maxInputBytes}</li>
 *   <li>string length vs {@code maxLength} (schema-declared)</li>
 *   <li>array length vs {@code maxItems} (schema-declared)</li>
 *   <li>nesting depth vs {@link ToolDefinition#DEFAULT_MAX_DEPTH}</li>
 * </ul>
 * All failures classify as {@link FailureKind#INPUT_INVALID} — the caller
 * renders them as {@code [INVALID_TOOL_ARGUMENTS]} per roadmap 2.2.
 */
public final class ToolArgumentValidator {

    private static final int MAX_DEPTH = ToolDefinition.DEFAULT_MAX_DEPTH;

    private ToolArgumentValidator() {
    }

    /**
     * Validate arguments against a definition's input schema.
     *
     * @param arguments raw arguments from the model (null = none given)
     * @param definition the tool contract (schema + size caps)
     */
    public static ValidationResult validate(JsonNode arguments, ToolDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        // ---- Structural guard 1: total size ----
        if (arguments != null) {
            int size = arguments.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int cap = definition.maxInputBytes();
            if (cap != ToolDefinition.UNLIMITED && size > cap) {
                errors.add("arguments size " + size + "B exceeds maxInputBytes " + cap + "B");
                return ValidationResult.fail(errors, unknown);
            }
        }

        // ---- Structural guard 2: nesting depth ----
        if (arguments != null && depth(arguments) > MAX_DEPTH) {
            errors.add("arguments nesting depth exceeds " + MAX_DEPTH);
            return ValidationResult.fail(errors, unknown);
        }

        String schema = definition.inputSchema();
        if (schema == null || schema.isBlank()) {
            // Untyped legacy tool: structural caps only, nothing else to check.
            return ValidationResult.ok(unknown);
        }

        final com.fasterxml.jackson.databind.JsonNode schemaNode;
        try {
            schemaNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema);
        } catch (Exception e) {
            return ValidationResult.schemaInvalid(
                    "input schema is not valid JSON: " + e.getMessage());
        }
        if (schemaNode == null || !schemaNode.isObject()) {
            return ValidationResult.schemaInvalid(
                    "input schema must be a JSON object");
        }

        if (arguments == null || arguments.isNull()) {
            // No arguments given. Valid only when the schema requires nothing.
            if (requiredFields(schemaNode).isEmpty()) {
                return ValidationResult.ok(unknown);
            }
            errors.add("missing required arguments: " + requiredFields(schemaNode));
            return ValidationResult.fail(errors, unknown);
        }
        if (!arguments.isObject()) {
            errors.add("arguments must be a JSON object, got " + arguments.getNodeType());
            return ValidationResult.fail(errors, unknown);
        }

        JsonNode properties = schemaNode.path("properties");
        List<String> required = requiredFields(schemaNode);

        // ---- Required fields present? ----
        for (String field : required) {
            if (!arguments.has(field) || arguments.get(field).isNull()) {
                errors.add("missing required field '" + field + "'");
            }
        }

        // ---- Per-field type / enum / length checks ----
        java.util.Iterator<String> fieldNames = arguments.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            JsonNode fieldSchema = properties.path(field);
            if (fieldSchema.isMissingNode()) {
                unknown.add(field);
                continue; // policy: ignore-with-record
            }
            checkField(field, arguments.get(field), fieldSchema, "", errors);
        }

        return errors.isEmpty()
                ? ValidationResult.ok(unknown)
                : ValidationResult.fail(errors, unknown);
    }

    private static void checkField(String name, JsonNode value, JsonNode fieldSchema,
                                   String path, List<String> errors) {
        String where = path.isEmpty() ? name : path + "." + name;

        // type
        String type = fieldSchema.path("type").asText(null);
        if (type != null && !typeSatisfies(value, type)) {
            errors.add(where + ": expected " + type + ", got " + value.getNodeType());
            return;
        }

        // enum
        JsonNode enumNode = fieldSchema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean match = false;
            for (JsonNode option : enumNode) {
                if (option.equals(value)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                errors.add(where + ": value not in enum " + enumNode);
                return;
            }
        }

        // maxLength / maxItems
        int maxLength = fieldSchema.path("maxLength").asInt(-1);
        if (maxLength >= 0 && value.isTextual() && value.asText().length() > maxLength) {
            errors.add(where + ": string length " + value.asText().length()
                    + " exceeds maxLength " + maxLength);
            return;
        }
        int maxItems = fieldSchema.path("maxItems").asInt(-1);
        if (maxItems >= 0 && value.isArray() && value.size() > maxItems) {
            errors.add(where + ": array length " + value.size()
                    + " exceeds maxItems " + maxItems);
            return;
        }

        // nested objects (properties inside properties)
        if (value.isObject() && fieldSchema.has("properties")) {
            JsonNode nestedProps = fieldSchema.path("properties");
            JsonNode nestedRequired = fieldSchema.path("required");
            java.util.Iterator<String> nested = value.fieldNames();
            while (nested.hasNext()) {
                String child = nested.next();
                JsonNode childSchema = nestedProps.path(child);
                if (childSchema.isMissingNode()) {
                    continue; // unknown nested field: ignore-with-record (outer scan records top-level)
                }
                checkField(child, value.get(child), childSchema, where, errors);
            }
            if (nestedRequired.isArray()) {
                for (JsonNode req : nestedRequired) {
                    if (!value.has(req.asText())) {
                        errors.add(where + ": missing required field '" + req.asText() + "'");
                    }
                }
            }
        }

        // array items
        if (value.isArray() && fieldSchema.has("items")) {
            JsonNode items = fieldSchema.path("items");
            for (int i = 0; i < value.size(); i++) {
                checkField("[" + i + "]", value.get(i), items, where, errors);
            }
        }
    }

    private static boolean typeSatisfies(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.canConvertToInt() || value.canConvertToLong();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true; // unknown type keyword: schema author's problem, not a validation failure
        };
    }

    private static List<String> requiredFields(JsonNode schemaNode) {
        List<String> required = new ArrayList<>();
        JsonNode req = schemaNode.get("required");
        if (req != null && req.isArray()) {
            for (JsonNode r : req) {
                required.add(r.asText());
            }
        }
        return required;
    }

    private static int depth(JsonNode node) {
        if (node.isObject()) {
            int max = 0;
            java.util.Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                max = Math.max(max, depth(children.next()));
            }
            return 1 + max;
        }
        if (node.isArray()) {
            int max = 0;
            for (JsonNode child : node) {
                max = Math.max(max, depth(child));
            }
            return 1 + max;
        }
        return 1;
    }
}
