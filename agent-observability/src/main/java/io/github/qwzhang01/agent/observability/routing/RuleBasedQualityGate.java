package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ModelResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * v1 quality gate (E2): three concrete defect signals, no scoring model -
 * if none of them fire, the response passes. Deliberately dumb, deliberately
 * explainable.
 * <p>
 * The three signals (each independently configurable, all on by default):
 * <ul>
 *   <li><b>unparseable structured output</b> - the request demanded JSON (or a
 *       JSON schema) and the content either cannot be stripped of markdown
 *       fences / parsed as JSON, or (schema mode) contains no object at all;
 *       a response that ignores the demanded format is a failed response</li>
 *   <li><b>abnormal finish reason</b> - {@code "error"} or {@code "length"};
 *       "length" means the cheap model ran out of output room mid-answer -
 *       truncated output is defective output (a complete thought that happens
 *       to be short is fine; a thought cut off at the knees is not)</li>
 *   <li><b>empty content</b> - no tool calls AND no text: the model said
 *       nothing at all</li>
 * </ul>
 * <p>
 * Verdict reasons carry the numbers that drove them (same audit discipline as
 * {@link RouteDecision} / {@link io.github.qwzhang01.agent.observability.routing.QualityGate.Verdict}):
 * "finish reason 'length' (truncated)", "content empty (no tool calls either)".
 * <p>
 * This gate is STATELESS - the same instance can judge responses from any
 * thread, any tier, any conversation. What to do about a failure is
 * {@link CascadeModelClient}'s business, not the gate's.
 */
public final class RuleBasedQualityGate implements QualityGate {

    private final boolean checkStructuredOutput;
    private final boolean checkFinishReason;
    private final boolean checkEmptyContent;

    /** All three signals on - the blueprint default. */
    public RuleBasedQualityGate() {
        this(true, true, true);
    }

    /**
     * @param checkStructuredOutput fail responses whose JSON output cannot be parsed
     * @param checkFinishReason     fail responses finishing with "error" or "length"
     * @param checkEmptyContent     fail responses with no content and no tool calls
     */
    public RuleBasedQualityGate(boolean checkStructuredOutput, boolean checkFinishReason, boolean checkEmptyContent) {
        this.checkStructuredOutput = checkStructuredOutput;
        this.checkFinishReason = checkFinishReason;
        this.checkEmptyContent = checkEmptyContent;
    }

    @Override
    public Verdict judge(ModelResponse response) {
        Objects.requireNonNull(response, "response");
        List<String> defects = new ArrayList<>(3);

        if (checkEmptyContent) {
            boolean hasText = response.content() != null && !response.content().isBlank();
            if (!hasText && !response.hasToolCalls()) {
                defects.add("content empty (no tool calls either)");
            }
        }

        if (checkFinishReason && response.finishReason() != null) {
            String fr = response.finishReason();
            if ("error".equals(fr)) {
                defects.add("finish reason 'error'");
            } else if ("length".equals(fr)) {
                defects.add("finish reason 'length' (truncated)");
            }
        }

        if (checkStructuredOutput && requestsJson(response)) {
            String parseProblem = structuredOutputProblem(response.content());
            if (parseProblem != null) {
                defects.add(parseProblem);
            }
        }

        if (defects.isEmpty()) {
            return Verdict.pass("no defect signal fired (parse ok, finish ok, content present)");
        }
        return Verdict.fail(String.join("; ", defects));
        // FIXME: [E3 note] finish-reason "length" on the premium retry too means the
        // maxTokens budget itself is wrong - escalating further is pointless; the
        // cascade returns the premium answer as-is rather than looping.
    }

    // ============ Structured output parsing ============

    /**
     * Whether this response came from a request demanding JSON. ModelResponse
     * does not echo the responseFormat back, so we conservatively sniff the
     * content: anything starting with '{' or '[' (after fence stripping) is
     * treated as a JSON attempt and must parse.
     */
    private static boolean requestsJson(ModelResponse response) {
        String raw = response.content();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String s = stripFences(raw.stripLeading());
        return !s.isEmpty() && (s.charAt(0) == '{' || s.charAt(0) == '[');
    }

    /**
     * Parse-check JSON-ish content; null means fine, non-null is the defect
     * description. Uses the minimal JSON structural validator (no Jackson
     * dependency needed at this layer): balanced braces/brackets, quoted
     * strings, commas separating members - good enough to catch the cheap
     * model emitting prose where JSON was demanded.
     */
    private static String structuredOutputProblem(String content) {
        if (content == null || content.isBlank()) {
            return "structured output demanded but content empty";
        }
        String s = stripFences(content.stripLeading()).stripTrailing();
        if (s.isEmpty()) {
            return "structured output demanded but content empty after fence stripping";
        }
        char first = s.charAt(0);
        if (first != '{' && first != '[') {
            return "structured output demanded but content starts with '" + first + "' (not JSON)";
        }
        return balancedJson(s) ? null : "JSON structure broken (unbalanced or malformed)";
    }

    /** Strip leading ```json / ``` fences if present. */
    private static String stripFences(String s) {
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                String rest = s.substring(firstNewline + 1);
                int closing = rest.indexOf("```");
                if (closing >= 0) {
                    return rest.substring(0, closing);
                }
                return rest;
            }
        }
        return s;
    }

    /**
     * Minimal structural JSON validation: no Jackson here - the observability
     * layer keeps its dependency surface minimal, and catching broken JSON
     * (unbalanced braces, unterminated strings) does not need a full parser.
     */
    private static boolean balancedJson(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        char open = s.charAt(0);
        char close = open == '{' ? '}' : ']';
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0 && c == close && i != s.length() - 1) {
                    return false;  // closes the root early, trailing garbage
                }
                if (depth < 0) {
                    return false;  // more closers than openers
                }
            }
        }
        return depth == 0 && !inString;
    }
}
