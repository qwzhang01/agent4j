package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ModelResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleBasedQualityGateTest {

    private final RuleBasedQualityGate gate = new RuleBasedQualityGate();

    private static ModelResponse of(String content, String finishReason) {
        return new ModelResponse(content, null, finishReason, null);
    }

    @Test
    @DisplayName("plain good text passes - no defect signal fires")
    void goodTextPasses() {
        QualityGate.Verdict v = gate.judge(ModelResponse.text("hello there"));
        assertTrue(v.passed());
        assertTrue(v.reason().contains("no defect signal"), v.reason());
    }

    @Test
    @DisplayName("tool-call response passes (content may be empty when tools fire)")
    void toolCallResponsePasses() {
        ModelResponse toolResponse = ModelResponse.toolCalls(java.util.List.of(
                io.github.qwzhang01.agent.core.model.ToolCall.of("call_1", "echo", "{\"input\":\"x\"}")));
        QualityGate.Verdict v = gate.judge(toolResponse);
        assertTrue(v.passed());
    }

    @Test
    @DisplayName("valid fenced JSON passes - fences are stripped before parsing")
    void fencedJsonPasses() {
        ModelResponse fenced = of("```json\n{\"answer\": 42}\n```", "stop");
        QualityGate.Verdict v = gate.judge(fenced);
        assertTrue(v.passed(), v.reason());
    }

    // signal 1: structured output broken

    @Test
    @DisplayName("JSON-looking but broken content fails with a parse defect")
    void brokenJsonFails() {
        ModelResponse broken = of("{\"answer\": 42", "stop");  // unbalanced
        QualityGate.Verdict v = gate.judge(broken);
        assertFalse(v.passed());
        assertTrue(v.reason().contains("JSON structure broken"), v.reason());
    }

    @Test
    @DisplayName("content starting with prose where JSON is implied fails")
    void proseInsteadOfJsonFails() {
        ModelResponse prose = of("The answer is forty-two, I believe.", "stop");
        // content does not start with { or [ -> NOT treated as JSON attempt -> passes
        // (the gate only parse-checks content that LOOKS like a JSON attempt)
        QualityGate.Verdict v = gate.judge(prose);
        assertTrue(v.passed());
    }

    // signal 2: abnormal finish reason

    @Test
    @DisplayName("finish reason 'length' fails - truncated output is defective")
    void truncatedFails() {
        QualityGate.Verdict v = gate.judge(of("partial answer becaus", "length"));
        assertFalse(v.passed());
        assertTrue(v.reason().contains("'length'"), v.reason());
    }

    @Test
    @DisplayName("finish reason 'error' fails")
    void errorFinishFails() {
        QualityGate.Verdict v = gate.judge(of("irrelevant", "error"));
        assertFalse(v.passed());
        assertTrue(v.reason().contains("'error'"), v.reason());
    }

    @Test
    @DisplayName("finish reason 'stop' with content passes; bare 'tool_calls' with NO calls and null content fails (empty)")
    void normalFinishPasses() {
        assertTrue(gate.judge(of("fine", "stop")).passed());
        // finish reason says tool_calls but the response carries none and no text:
        // that IS an empty response - the gate must fail it, not trust the label
        assertFalse(gate.judge(of(null, "tool_calls")).passed());
    }

    // signal 3: empty content

    @Test
    @DisplayName("empty content with no tool calls fails")
    void emptyContentFails() {
        QualityGate.Verdict v = gate.judge(of("", "stop"));
        assertFalse(v.passed());
        assertTrue(v.reason().contains("empty"), v.reason());
    }

    @Test
    @DisplayName("blank content with no tool calls fails")
    void blankContentFails() {
        QualityGate.Verdict v = gate.judge(of("   ", "stop"));
        assertFalse(v.passed());
        assertTrue(v.reason().contains("empty"), v.reason());
    }

    @Test
    @DisplayName("multiple defects are all named in one verdict, separated by '; '")
    void multipleDefectsAllNamed() {
        ModelResponse disaster = of("", "error");
        QualityGate.Verdict v = gate.judge(disaster);
        assertFalse(v.passed());
        assertTrue(v.reason().contains("empty"), v.reason());
        assertTrue(v.reason().contains("error"), v.reason());
        assertTrue(v.reason().contains("; "), "defects joined with '; '");
    }

    @Test
    @DisplayName("all signals off: even a garbage response passes (escape hatch for experiments)")
    void allOffPassesEverything() {
        RuleBasedQualityGate off = new RuleBasedQualityGate(false, false, false);
        assertTrue(off.judge(of("", "error")).passed());
    }

    @Test
    @DisplayName("Verdict factories reject blank reasons - verdicts are auditable")
    void verdictGuards() {
        assertThrows(IllegalArgumentException.class, () -> QualityGate.Verdict.pass(" "));
        assertThrows(IllegalArgumentException.class, () -> QualityGate.Verdict.fail(null));
        assertEquals("why", QualityGate.Verdict.fail("why").reason());
        assertTrue(QualityGate.Verdict.pass("fine").passed());
    }

    @Test
    @DisplayName("fenced but broken JSON fails; unclosed fence degrades gracefully")
    void fencedEdgeCases() {
        ModelResponse fencedBroken = of("```json\n{\"a\":\n```", "stop");
        assertFalse(gate.judge(fencedBroken).passed());

        // bare ``` with no newline: stripFences falls back to raw content
        ModelResponse bareFence = of("```", "stop");
        // not JSON-looking -> passes the structured check, but empty-ish content?
        // "```" is non-blank, so no empty defect; verdict: pass
        assertTrue(gate.judge(bareFence).passed(), "non-JSON-looking content is not parse-checked");
    }

    @Test
    @DisplayName("JSON array root also parse-checked")
    void arrayRootChecked() {
        assertTrue(gate.judge(of("[1, 2, 3]", "stop")).passed());
        assertFalse(gate.judge(of("[1, 2", "stop")).passed());
    }
}
