package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6.2: minimal schema validation at the MCP border — required fields
 * and declared primitive types. Deliberately not a full JSON Schema
 * engine; unknown keywords fail open (documented gap).
 */
class McpSchemaValidatorTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void validArguments_pass() throws Exception {
        var schema = M.readTree("""
                {"type":"object","required":["city"],
                 "properties":{"city":{"type":"string"},"days":{"type":"integer"}}}
                """);
        var args = M.readTree("{\"city\":\"SF\",\"days\":3}");
        assertEquals(List.of(), McpSchemaValidator.validate(schema, args));
    }

    @Test
    void missingRequiredField_isViolation() throws Exception {
        var schema = M.readTree("""
                {"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}
                """);
        var args = M.readTree("{\"days\":1}");
        List<String> violations = McpSchemaValidator.validate(schema, args);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("city"));
    }

    @Test
    void wrongType_isViolation() throws Exception {
        var schema = M.readTree("""
                {"type":"object","properties":{"days":{"type":"integer"}}}
                """);
        var args = M.readTree("{\"days\":\"three\"}");
        List<String> violations = McpSchemaValidator.validate(schema, args);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("expects integer"));
    }

    @Test
    void nullArguments_requiredFieldsStillChecked() throws Exception {
        var schema = M.readTree("{\"type\":\"object\",\"required\":[\"q\"]}");
        List<String> violations = McpSchemaValidator.validate(schema, null);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("q"));
    }

    @Test
    void noSchema_failsOpen() throws Exception {
        var args = M.readTree("{\"anything\":\"goes\"}");
        assertEquals(List.of(), McpSchemaValidator.validate(null, args));
        assertEquals(List.of(), McpSchemaValidator.validate(
                M.readTree("\"not an object\""), args));
    }

    @Test
    void nonObjectArguments_isViolation() throws Exception {
        var schema = M.readTree("{\"type\":\"object\"}");
        var args = M.readTree("[1,2,3]");
        List<String> violations = McpSchemaValidator.validate(schema, args);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("must be a JSON object"));
    }

    @Test
    void unknownKeywords_failOpen() throws Exception {
        // $ref / pattern / minimum are beyond the minimal core: they must not
        // crash and must not invent violations.
        var schema = M.readTree("""
                {"type":"object","properties":{"x":{"$ref":"#/defs/x","pattern":"^a"}}}
                """);
        var args = M.readTree("{\"x\":\"zzz\"}");
        assertEquals(List.of(), McpSchemaValidator.validate(schema, args));
    }

    @Test
    void validateOrThrow_aggregatesViolations() throws Exception {
        var schema = M.readTree("""
                {"type":"object","required":["a","b"],
                 "properties":{"a":{"type":"string"},"c":{"type":"boolean"}}}
                """);
        var args = M.readTree("{\"a\":1}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> McpSchemaValidator.validateOrThrow(schema, args));
        assertTrue(ex.getMessage().contains("a"), "should mention missing field");
        assertTrue(ex.getMessage().contains("expects string"), "should mention type mismatch");
    }
}
