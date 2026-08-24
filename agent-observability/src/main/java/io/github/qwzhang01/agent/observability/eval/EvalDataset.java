package io.github.qwzhang01.agent.observability.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The regression dataset (Stage 18 D7): a set of {@link EvalCase}s, two ways
 * to fill it - hand-written, or mined from failure trajectories - plus the
 * JSONL persistence that makes a dataset a long-lived asset.
 * <p>
 * Failure mining is the "fix one bug = dataset +1 case" loop's landing point:
 * trajectories whose terminal reason is {@code ERROR}/{@code MAX_STEPS_EXCEEDED},
 * or whose reward sits below the threshold, become cases whose
 * {@code originRunId} points back at the incident. Three honest skip rules
 * (documented, not silent): trajectories with no extractable USER prompt
 * cannot become cases; the count returned reflects only what was imported.
 * <p>
 * Translating a failure SHAPE into an assertion is domain knowledge the
 * framework does not have ("the answer must now contain an apology", "the
 * tool must be called at most twice") - so {@link #importFailures} takes the
 * translation as a function. A default translation would either fabricate
 * assertions or make them vacuous; both are worse than asking the operator
 * once.
 * <p>
 * JSONL contract (hand-built trees, the Stage 14 TrajectoryCodec discipline:
 * every field name explicit, snake_case, never inferred):
 * <pre>{@code
 * {"api_version":"v1","kind":"EvalCase","case_id":"case-0001","prompt":"...",
 *  "origin_run_id":"run-8842","expectation":{"type":"contains","fragment":"道歉"}}
 * }</pre>
 */
public final class EvalDataset {

    /** On-disk schema version (changing a field name is a bump, not an edit). */
    public static final String API_VERSION = "v1";
    public static final String KIND = "EvalCase";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<EvalCase> cases = new ArrayList<>();

    // ============ Construction ============

    public static EvalDataset empty() {
        return new EvalDataset();
    }

    public static EvalDataset of(EvalCase... seed) {
        EvalDataset dataset = new EvalDataset();
        for (EvalCase c : seed) {
            dataset.add(c);
        }
        return dataset;
    }

    /** Append a case; duplicate caseIds are rejected fail-fast (gate keys on them). */
    public EvalDataset add(EvalCase evalCase) {
        Objects.requireNonNull(evalCase, "evalCase");
        boolean duplicate = cases.stream().anyMatch(c -> c.caseId().equals(evalCase.caseId()));
        if (duplicate) {
            throw new IllegalArgumentException("duplicate caseId: " + evalCase.caseId());
        }
        cases.add(evalCase);
        return this;
    }

    public List<EvalCase> cases() {
        return List.copyOf(cases);
    }

    public int size() {
        return cases.size();
    }

    // ============ Failure mining (D7) ============

    /**
     * Mine failure trajectories into regression cases.
     * <p>
     * A trajectory qualifies when its terminal reason is {@code ERROR} or
     * {@code MAX_STEPS_EXCEEDED}, OR its (non-null) reward is below
     * {@code minReward}. The prompt is the first USER message of the logical
     * conversation; the case's {@code originRunId} is the trajectory's run id
     * (the bug-case lineage).
     *
     * @param expectationFor translates a qualifying trajectory into its
     *                       assertion - failure shape to expectation is domain
     *                       knowledge, supplied by the operator (see class doc)
     * @return how many cases were imported
     */
    public int importFailures(List<Trajectory> trajectories, double minReward,
                              Function<Trajectory, Expectation> expectationFor) {
        Objects.requireNonNull(trajectories, "trajectories");
        Objects.requireNonNull(expectationFor, "expectationFor");
        int imported = 0;
        for (Trajectory trajectory : trajectories) {
            if (!isFailure(trajectory, minReward)) {
                continue;
            }
            String prompt = firstUserPrompt(trajectory);
            if (prompt == null) {
                continue;  // no replayable prompt - not case material, skipped honestly
            }
            Expectation expectation = Objects.requireNonNull(expectationFor.apply(trajectory),
                    "expectationFor returned null for " + trajectory.runId());
            add(new EvalCase(nextCaseId(), prompt, expectation, trajectory.runId()));
            imported++;
        }
        return imported;
    }

    static boolean isFailure(Trajectory trajectory, double minReward) {
        DoneReason reason = trajectory.doneReason();
        if (reason == DoneReason.ERROR || reason == DoneReason.MAX_STEPS_EXCEEDED) {
            return true;
        }
        return trajectory.reward() != null && trajectory.reward() < minReward;
    }

    static String firstUserPrompt(Trajectory trajectory) {
        for (ChatMessage message : trajectory.messages()) {
            if (message.role() == ChatRole.USER && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return null;
    }

    private String nextCaseId() {
        // hand-written ids may already occupy the generated form; keep counting until free
        int next = cases.size() + 1;
        while (isCaseIdTaken(String.format("case-%04d", next))) {
            next++;
        }
        return String.format("case-%04d", next);
    }

    private boolean isCaseIdTaken(String candidate) {
        for (EvalCase c : cases) {
            if (c.caseId().equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    // ============ JSONL persistence ============

    /** One case per line, UTF-8. Overwrites the target file. */
    public void save(Path jsonlFile) throws IOException {
        Objects.requireNonNull(jsonlFile, "jsonlFile");
        StringBuilder sb = new StringBuilder();
        for (EvalCase evalCase : cases) {
            sb.append(toJson(evalCase).toString()).append('\n');
        }
        if (jsonlFile.getParent() != null) {
            Files.createDirectories(jsonlFile.getParent());
        }
        Files.writeString(jsonlFile, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Load a JSONL file; malformed lines fail loud with their line number. */
    public static EvalDataset load(Path jsonlFile) throws IOException {
        Objects.requireNonNull(jsonlFile, "jsonlFile");
        EvalDataset dataset = new EvalDataset();
        List<String> lines = Files.readAllLines(jsonlFile, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                dataset.add(fromJson(MAPPER.readTree(line)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": " + e.getMessage(), e);
            } catch (Exception e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": invalid JSON: " + e.getMessage(), e);
            }
        }
        return dataset;
    }

    // ============ Case JSON (hand-built, TrajectoryCodec discipline) ============

    ObjectNode toJson(EvalCase evalCase) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("api_version", API_VERSION);
        node.put("kind", KIND);
        node.put("case_id", evalCase.caseId());
        node.put("prompt", evalCase.prompt());
        if (evalCase.originRunId() != null) {
            node.put("origin_run_id", evalCase.originRunId());
        }
        node.set("expectation", expectationToJson(evalCase.expectation()));
        return node;
    }

    static EvalCase fromJson(JsonNode node) {
        requiredField(node, "api_version");
        if (!API_VERSION.equals(node.get("api_version").asText())) {
            throw new IllegalArgumentException("unsupported api_version: " + node.get("api_version").asText());
        }
        if (node.has("kind") && !KIND.equals(node.get("kind").asText())) {
            throw new IllegalArgumentException("unexpected kind: " + node.get("kind").asText());
        }
        String caseId = requiredText(node, "case_id");
        String prompt = requiredText(node, "prompt");
        String originRunId = node.hasNonNull("origin_run_id") ? node.get("origin_run_id").asText() : null;
        Expectation expectation = expectationFromJson(requiredField(node, "expectation"));
        return new EvalCase(caseId, prompt, expectation, originRunId);
    }

    // ============ Expectation JSON ============

    private ObjectNode expectationToJson(Expectation expectation) {
        ObjectNode node = MAPPER.createObjectNode();
        if (expectation instanceof Expectation.ExactMatch e) {
            node.put("type", "exact_match").put("expected", e.expected());
        } else if (expectation instanceof Expectation.Contains c) {
            node.put("type", "contains").put("fragment", c.fragment());
        } else if (expectation instanceof Expectation.MaxTokens m) {
            node.put("type", "max_tokens").put("max", m.max());
        } else if (expectation instanceof Expectation.ToolCallCount t) {
            node.put("type", "tool_call_count").put("expected", t.expected());
        } else {
            throw new IllegalArgumentException("unknown expectation type: " + expectation.getClass().getName());
        }
        return node;
    }

    private static Expectation expectationFromJson(JsonNode node) {
        String type = requiredText(node, "type");
        return switch (type) {
            case "exact_match" -> new Expectation.ExactMatch(requiredText(node, "expected"));
            case "contains" -> new Expectation.Contains(requiredText(node, "fragment"));
            case "max_tokens" -> new Expectation.MaxTokens(requiredLong(node, "max"));
            case "tool_call_count" -> new Expectation.ToolCallCount((int) requiredLong(node, "expected"));
            default -> throw new IllegalArgumentException("unknown expectation type: " + type);
        };
    }

    // ============ Field helpers ============

    private static JsonNode requiredField(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || field.isNull()) {
            throw new IllegalArgumentException("missing field: " + name);
        }
        return field;
    }

    private static String requiredText(JsonNode node, String name) {
        return requiredField(node, name).asText();
    }

    private static long requiredLong(JsonNode node, String name) {
        JsonNode field = requiredField(node, name);
        if (!field.canConvertToLong()) {
            throw new IllegalArgumentException("field is not a number: " + name);
        }
        return field.asLong();
    }
}
