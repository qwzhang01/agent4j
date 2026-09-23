package io.github.qwzhang01.agent.model.openai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  pins the capability declaration against the client's actual
 * behavior branches. If {@link OpenAiModelClient}'s flavor switch changes,
 * this table must change with it — that coupling is the point.
 */
class ProviderCapabilitiesTest {

    @Test
    void openai_nativeJsonAndReasoningEffort() {
        ProviderCapabilities caps = ProviderCapabilities.forFlavor(
                OpenAiModelClient.Flavor.OPENAI);
        assertTrue(caps.nativeJsonMode());
        assertTrue(caps.nativeReasoningSwitch());
        assertEquals("reasoning_effort", caps.reasoningField());
        assertTrue(caps.parsesReasoningContent());
    }

    @Test
    void ark_thinkingFieldNoReasoningParse() {
        ProviderCapabilities caps = ProviderCapabilities.forFlavor(
                OpenAiModelClient.Flavor.ARK);
        assertFalse(caps.nativeJsonMode(), "ARK is coerced to prompt-instruction JSON");
        assertTrue(caps.nativeReasoningSwitch());
        assertEquals("thinking", caps.reasoningField());
        assertFalse(caps.parsesReasoningContent());
    }

    @Test
    void qwen_enableThinkingToggle() {
        ProviderCapabilities caps = ProviderCapabilities.forFlavor(
                OpenAiModelClient.Flavor.QWEN);
        assertEquals("enable_thinking", caps.reasoningField());
        assertTrue(caps.nativeReasoningSwitch());
    }

    @Test
    void openrouter_reasoningObjectWithParsing() {
        ProviderCapabilities caps = ProviderCapabilities.forFlavor(
                OpenAiModelClient.Flavor.OPENROUTER);
        assertEquals("reasoning", caps.reasoningField());
        assertTrue(caps.parsesReasoningContent());
        assertTrue(caps.nativeJsonMode());
    }

    @Test
    void deepseekAndGeneric_noReasoningSwitch() {
        for (OpenAiModelClient.Flavor flavor : new OpenAiModelClient.Flavor[]{
                OpenAiModelClient.Flavor.DEEPSEEK, OpenAiModelClient.Flavor.GENERIC}) {
            ProviderCapabilities caps = ProviderCapabilities.forFlavor(flavor);
            assertFalse(caps.nativeReasoningSwitch(),
                    flavor + " has no native reasoning switch (warn + extraBody escape)");
            assertNull(caps.reasoningField());
            assertTrue(caps.parsesReasoningContent(),
                    flavor + " reasoning output is still parsed out of the answer");
        }
    }

    @Test
    void allFlavors_extraBodyEscapeHatchIsStrict() {
        for (OpenAiModelClient.Flavor flavor : OpenAiModelClient.Flavor.values()) {
            ProviderCapabilities caps = ProviderCapabilities.forFlavor(flavor);
            assertTrue(caps.strictExtraBodyEscapeHatch(),
                    flavor + ": extraBody merges, standard fields win on collision");
        }
    }

    @Test
    void allFlavors_declaredExactlyOnce() {
        // One shared vocabulary: every client Flavor has a declaration and
        // the record echoes it back — no silent drift between the two.
        for (OpenAiModelClient.Flavor flavor : OpenAiModelClient.Flavor.values()) {
            assertEquals(flavor, ProviderCapabilities.forFlavor(flavor).flavor());
        }
    }
}
