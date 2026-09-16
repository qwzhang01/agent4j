package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 6.2: schema validation for MCP tools at the border.
 * <p>
 * Roadmap: "MCP Tool 接入统一 ToolDefinition 和 Schema Validator". An MCP
 * server hands us an {@code inputSchema} with its tool listing; before the
 * tool is callable, arguments must be checked against that schema — a
 * malformed argument reaching a remote server is both a correctness bug
 * and an injection surface.
 * <p>
 * Honest scope: this is a MINIMAL structural validator — required fields
 * present, declared primitive types respected. It is NOT a full JSON
 * Schema implementation (no $ref, no allOf/anyOf, no pattern, no
 * numeric ranges). Servers in the wild publish mostly plain
 * object-schemas; the minimal core covers that reality without dragging a
 * validation library into the dependency tree. Anything beyond it fails
 * OPEN (validated loosely) and the gap is tracked in notes/harness-gap.
 */
public final class McpSchemaValidator {

    private McpSchemaValidator() {
    }

    /**
     * Validate arguments against a tool's inputSchema.
     *
     * @param schema the tool's declared inputSchema (may be null → only
     *               required-field checks are skipped, arguments pass)
     * @param args   the arguments about to be sent
     * @return violation messages; empty list = valid
     */
    public static List<String> validate(JsonNode schema, JsonNode args) {
        List<String> violations = new ArrayList<>();
        if (schema == null || schema.isMissingNode() || !schema.isObject()) {
            return violations; // no schema declared: nothing to enforce
        }
        final JsonNode arguments;
        if (args == null || args.isNull()) {
            arguments = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        } else {
            arguments = args;
        }
        if (!arguments.isObject()) {
            violations.add("arguments must be a JSON object, got "
                    + arguments.getNodeType());
            return violations;
        }

        // Required fields must be present
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode req : required) {
                String field = req.asText();
                if (!arguments.has(field) || arguments.get(field).isNull()) {
                    violations.add("missing required field '" + field + "'");
                }
            }
        }

        // Declared primitive types respected (unknown keywords ignored)
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                JsonNode fieldSchema = entry.getValue();
                if (!arguments.has(fieldName) || fieldSchema == null || !fieldSchema.isObject()) {
                    return;
                }
                String declaredType = fieldSchema.path("type").asText(null);
                if (declaredType == null) {
                    return;
                }
                String actualType = jsonTypeName(arguments.get(fieldName));
                if (!actualType.equals(declaredType)) {
                    violations.add("field '" + fieldName + "' expects " + declaredType
                            + " but got " + actualType);
                }
            });
        }
        return violations;
    }

    /** Validate and throw on the first violation batch (for the adapter). */
    public static void validateOrThrow(JsonNode schema, JsonNode args) {
        List<String> violations = validate(schema, args);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "MCP tool arguments violate inputSchema: " + String.join("; ", violations));
        }
    }

    private static String jsonTypeName(JsonNode node) {
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isInt() || node.isLong() || node.isBigInteger()) {
            return "integer";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isObject()) {
            return "object";
        }
        return node.getNodeType().toString().toLowerCase();
    }
}
