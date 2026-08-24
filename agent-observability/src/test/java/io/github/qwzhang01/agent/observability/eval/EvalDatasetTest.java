package io.github.qwzhang01.agent.observability.eval;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.StepAction;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryMetadata;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalDatasetTest {

    // ============ Trajectory fixture (minimal, both channels consistent) ============

    private static Trajectory trajectory(String runId, DoneReason doneReason, Double reward, String userPrompt) {
        ChatMessage user = userPrompt == null ? null : ChatMessage.user(userPrompt);
        List<ChatMessage> state = user == null
                ? List.of(ChatMessage.system("you are a helper"))
                : List.of(ChatMessage.system("you are a helper"), user);
        TrajectoryStep step = new TrajectoryStep(1, state,
                new StepAction("final answer", null, "stop", null, 5),
                List.of(), null, true, doneReason);
        List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(ChatMessage.system("you are a helper"));
        if (user != null) {
            messages.add(user);
        }
        messages.add(ChatMessage.assistant("final answer"));
        return new Trajectory("tid-" + runId, runId,
                new TrajectoryMetadata(null, null, List.of(), null, null, null, 0, null, null, Map.of()),
                AgentState.Status.DONE, List.of(step), messages, reward, "rule");
    }

    // ============ dataset basics ============

    @Test
    @DisplayName("add/cases/size: order preserved, cases() is a defensive copy")
    void basics() {
        EvalDataset dataset = EvalDataset.of(
                EvalCase.of("c1", "hello", new Expectation.Contains("hi")),
                EvalCase.of("c2", "bye", new Expectation.ExactMatch("bye")));

        assertEquals(2, dataset.size());
        assertEquals("c1", dataset.cases().get(0).caseId());
        assertThrows(UnsupportedOperationException.class, () -> dataset.cases().add(null));
    }

    @Test
    @DisplayName("duplicate caseId rejected fail-fast (the gate keys on ids)")
    void duplicateCaseIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.of(
                EvalCase.of("c1", "a", new Expectation.Contains("x")),
                EvalCase.of("c1", "b", new Expectation.Contains("y"))));
    }

    @Test
    @DisplayName("EvalCase guards: blank caseId/prompt, null expectation")
    void evalCaseGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> EvalCase.of(" ", "p", new Expectation.Contains("x")));
        assertThrows(IllegalArgumentException.class,
                () -> EvalCase.of("c1", "", new Expectation.Contains("x")));
        assertThrows(NullPointerException.class,
                () -> EvalCase.of("c1", "p", null));
    }

    // ============ failure mining: the three sources ============

    @Test
    @DisplayName("importFailures: ERROR / MAX_STEPS_EXCEEDED / low reward all become cases; healthy DONE excluded")
    void threeFailureSources() {
        List<Trajectory> trajectories = List.of(
                trajectory("run-err", DoneReason.ERROR, null, "帮我查天气"),
                trajectory("run-max", DoneReason.MAX_STEPS_EXCEEDED, null, "修复这个 bug"),
                trajectory("run-low", DoneReason.DONE, -0.8, "总结报告"),
                trajectory("run-good", DoneReason.DONE, 0.9, "健康运行不应进数据集"),
                trajectory("run-unrewarded", DoneReason.DONE, null, "无 reward 的正常完成也不进"));

        EvalDataset dataset = EvalDataset.empty();
        int imported = dataset.importFailures(trajectories, -0.4, t -> new Expectation.Contains("道歉"));

        assertEquals(3, imported, "ERROR + MAX_STEPS + low-reward qualify; DONE-good and DONE-unrewarded do not");
        List<String> prompts = dataset.cases().stream().map(EvalCase::prompt).toList();
        assertEquals(List.of("帮我查天气", "修复这个 bug", "总结报告"), prompts);
    }

    @Test
    @DisplayName("importFailures: originRunId points back at the incident (bug-case lineage)")
    void lineagePreserved() {
        EvalDataset dataset = EvalDataset.empty();
        dataset.importFailures(List.of(trajectory("run-8842", DoneReason.ERROR, null, "出错了")),
                -0.4, t -> new Expectation.Contains("道歉"));

        EvalCase imported = dataset.cases().get(0);
        assertEquals("run-8842", imported.originRunId(), "用例与事故的谱系不断");
        assertEquals("case-0001", imported.caseId());
    }

    @Test
    @DisplayName("importFailures: failure-shape translation is the operator's function (D7)")
    void expectationTranslationInjected() {
        EvalDataset dataset = EvalDataset.empty();
        dataset.importFailures(
                List.of(trajectory("run-1", DoneReason.MAX_STEPS_EXCEEDED, null, "跑飞了")),
                -0.4,
                t -> t.doneReason() == DoneReason.MAX_STEPS_EXCEEDED
                        ? new Expectation.ToolCallCount(2)
                        : new Expectation.Contains("?"));

        assertTrue(dataset.cases().get(0).expectation() instanceof Expectation.ToolCallCount);
    }

    @Test
    @DisplayName("importFailures: trajectory without a USER prompt is skipped honestly (count reflects it)")
    void noUserPromptSkipped() {
        EvalDataset dataset = EvalDataset.empty();
        int imported = dataset.importFailures(
                List.of(trajectory("run-silent", DoneReason.ERROR, null, null)),
                -0.4, t -> new Expectation.Contains("x"));

        assertEquals(0, imported);
        assertEquals(0, dataset.size());
    }

    @Test
    @DisplayName("importFailures: generated caseId skips ids already taken by hand-written cases")
    void caseIdCollisionSkipped() {
        EvalDataset dataset = EvalDataset.of(
                EvalCase.of("case-0001", "手工用例占住了一号位", new Expectation.Contains("x")));

        dataset.importFailures(List.of(trajectory("run-9", DoneReason.ERROR, null, "新的失败")),
                -0.4, t -> new Expectation.Contains("y"));

        assertEquals("case-0002", dataset.cases().get(1).caseId());
    }

    // ============ JSONL round-trip ============

    @Test
    @DisplayName("JSONL round-trip: all four expectation types, with and without lineage")
    void jsonlRoundTrip() throws IOException {
        EvalDataset dataset = EvalDataset.of(
                new EvalCase("case-0001", "prompt-1", new Expectation.ExactMatch("42"), "run-7"),
                EvalCase.of("case-0002", "prompt-2", new Expectation.Contains("道歉")),
                EvalCase.of("case-0003", "prompt-3", new Expectation.MaxTokens(500)),
                EvalCase.of("case-0004", "prompt-4", new Expectation.ToolCallCount(2)));
        Path file = Files.createTempFile("eval-dataset", ".jsonl");

        dataset.save(file);
        EvalDataset loaded = EvalDataset.load(file);

        assertEquals(dataset.cases(), loaded.cases(), "round-trip must be lossless");
        assertEquals("run-7", loaded.cases().get(0).originRunId());
        assertNull(loaded.cases().get(1).originRunId());
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("JSONL format: snake_case envelope with api_version/kind (contract snapshot)")
    void jsonlFormatSnapshot() throws IOException {
        EvalDataset dataset = EvalDataset.of(
                new EvalCase("case-0001", "帮我总结", new Expectation.Contains("道歉"), "run-8842"));
        Path file = Files.createTempFile("eval-format", ".jsonl");

        dataset.save(file);
        String line = Files.readString(file).trim();

        assertTrue(line.startsWith("{\"api_version\":\"v1\",\"kind\":\"EvalCase\""), line);
        assertTrue(line.contains("\"case_id\":\"case-0001\""));
        assertTrue(line.contains("\"origin_run_id\":\"run-8842\""));
        assertTrue(line.contains("\"expectation\":{\"type\":\"contains\",\"fragment\":\"道歉\"}"));
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("load fails loud: bad JSON and contract violations carry the line number")
    void loadFailsLoud() throws IOException {
        Path bad = Files.createTempFile("eval-bad", ".jsonl");
        Files.writeString(bad, "{\"api_version\":\"v1\",\"kind\":\"EvalCase\",\"case_id\":\"c1\",\"prompt\":\"p\","
                + "\"expectation\":{\"type\":\"contains\",\"fragment\":\"x\"}}\n"
                + "not-json-at-all\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EvalDataset.load(bad));
        assertTrue(e.getMessage().contains("line 2"), "line number surfaces: " + e.getMessage());
        Files.deleteIfExists(bad);
    }

    @Test
    @DisplayName("load fails loud: unsupported api_version / unknown expectation type / duplicate ids")
    void loadContractViolations() throws IOException {
        Path file = Files.createTempFile("eval-contract", ".jsonl");
        Files.writeString(file, "{\"api_version\":\"v9\",\"kind\":\"EvalCase\",\"case_id\":\"c\",\"prompt\":\"p\","
                + "\"expectation\":{\"type\":\"contains\",\"fragment\":\"x\"}}\n");
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.load(file));

        Files.writeString(file, "{\"api_version\":\"v1\",\"kind\":\"EvalCase\",\"case_id\":\"c\",\"prompt\":\"p\","
                + "\"expectation\":{\"type\":\"judge_vibes\"}}\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EvalDataset.load(file));
        assertTrue(e.getMessage().contains("judge_vibes"));

        Files.writeString(file,
                "{\"api_version\":\"v1\",\"kind\":\"EvalCase\",\"case_id\":\"c\",\"prompt\":\"p\","
                        + "\"expectation\":{\"type\":\"contains\",\"fragment\":\"x\"}}\n"
                        + "{\"api_version\":\"v1\",\"kind\":\"EvalCase\",\"case_id\":\"c\",\"prompt\":\"q\","
                        + "\"expectation\":{\"type\":\"contains\",\"fragment\":\"y\"}}\n");
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.load(file), "duplicate ids in one file");
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("load: blank lines are skipped (JSONL convention)")
    void loadSkipsBlankLines() throws IOException {
        Path file = Files.createTempFile("eval-blank", ".jsonl");
        Files.writeString(file, "\n"
                + "{\"api_version\":\"v1\",\"kind\":\"EvalCase\",\"case_id\":\"c1\",\"prompt\":\"p\","
                + "\"expectation\":{\"type\":\"contains\",\"fragment\":\"x\"}}\n\n");

        assertEquals(1, EvalDataset.load(file).size());
        Files.deleteIfExists(file);
    }
}
