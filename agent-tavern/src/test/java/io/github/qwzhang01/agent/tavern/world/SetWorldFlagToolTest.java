package io.github.qwzhang01.agent.tavern.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.tool.ToolException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.2: the world tool is a pure instruction submitter.
 * <p>
 * Blueprint D3 under test: the tool never applies an effect - it submits one
 * to the engine-provided sink. A collecting sink makes the whole contract
 * testable without any engine in sight.
 */
class SetWorldFlagToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("execute submits a SetFlag instruction to the sink and confirms in text")
    void submitsInstruction() throws Exception {
        List<WorldEffect> submitted = new ArrayList<>();
        SetWorldFlagTool tool = new SetWorldFlagTool(submitted::add);

        String result = tool.execute(mapper.readTree("{\"key\":\"bard-mood\",\"value\":\"lively\"}"));

        assertEquals(1, submitted.size());
        WorldEffect.SetFlag flag = (WorldEffect.SetFlag) submitted.get(0);
        assertEquals("bard-mood", flag.key());
        assertEquals("lively", flag.value());
        assertEquals("World flag 'bard-mood' set to 'lively'.", result);
    }

    @Test
    @DisplayName("missing key or value (or null arguments) is a ToolException, not a crash")
    void missingArgumentsRejected() {
        List<WorldEffect> submitted = new ArrayList<>();
        SetWorldFlagTool tool = new SetWorldFlagTool(submitted::add);

        assertThrows(ToolException.class, () -> tool.execute(null));
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.createObjectNode().put("key", "k")));
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.createObjectNode().put("value", "v")));
        assertEquals(0, submitted.size(), "a rejected call must not submit anything");
    }

    @Test
    @DisplayName("blank key/value is rejected as ToolException (record validation, translated)")
    void blankValuesRejected() {
        List<WorldEffect> submitted = new ArrayList<>();
        SetWorldFlagTool tool = new SetWorldFlagTool(submitted::add);

        assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"key\":\" \",\"value\":\"v\"}")));
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.readTree("{\"key\":\"k\",\"value\":\"\"}")));
        assertEquals(0, submitted.size());
    }

    @Test
    @DisplayName("tool metadata is model-facing and honest")
    void metadata() {
        SetWorldFlagTool tool = new SetWorldFlagTool(e -> { });

        assertEquals("set_world_flag", tool.getName());
        assertTrue(tool.getDescription().contains("world"));
        assertTrue(tool.getParametersSchema().contains("\"key\""));
        assertTrue(tool.getParametersSchema().contains("\"required\""));
    }
}
