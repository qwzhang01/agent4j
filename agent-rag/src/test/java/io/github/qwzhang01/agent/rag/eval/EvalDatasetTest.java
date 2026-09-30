package io.github.qwzhang01.agent.rag.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalDatasetTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("set.jsonl");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void toleratesBomAndCrlf() throws IOException {
        List<EvalCase> cases = EvalDataset.load(write(
                "\uFEFF{\"id\":\"q1\",\"question\":\"召回\"}\r\n\r\n{\"id\":\"q2\",\"question\":\"重排\"}\r\n"));
        assertEquals(List.of("q1", "q2"), cases.stream().map(EvalCase::id).toList());
        assertEquals("召回", cases.get(0).question());
    }

    @Test
    void loadsAllFieldsSkippingBlankAndCommentLines() throws IOException {
        Path file = write("""
                // eval set v1

                {"id":"q001","type":"single_hop","question":"召回怎么做？","relevant":[{"docId":"stage-8.md","section":"召回"}],"answer":"两路","answerable":true}
                {"id":"q002","type":"multi_turn","question":"那重排呢？","history":[{"role":"user","content":"召回怎么做"},{"role":"assistant","content":"两路"}],"relevant":[{"docId":"stage-9.md"}]}
                {"id":"q003","type":"unanswerable","question":"股价多少？","relevant":[]}
                {"id":"q004","type":"new_kind","question":"x","relevant":[{"docId":"a.md","section":""}],"extra":1}
                """);
        List<EvalCase> cases = EvalDataset.load(file);
        assertEquals(4, cases.size());
        EvalCase q1 = cases.get(0);
        assertEquals("single_hop", q1.type());
        assertEquals(new GoldRef("stage-8.md", "召回"), q1.relevant().get(0));
        assertEquals("两路", q1.answer());
        assertTrue(q1.answerable());
        EvalCase q2 = cases.get(1);
        assertEquals(2, q2.history().size());
        assertEquals("assistant", q2.history().get(1).role());
        assertNull(q2.relevant().get(0).section());
        assertTrue(q2.answerable());
        assertFalse(cases.get(2).answerable());
        assertTrue(cases.get(2).relevant().isEmpty());
        assertEquals("new_kind", cases.get(3).type());
        assertNull(cases.get(3).relevant().get(0).section());
    }

    @Test
    void badJsonReportsLineNumber() throws IOException {
        Path file = write("""
                {"id":"q1","question":"a"}
                // comment
                {"id":"q2","question": oops}
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EvalDataset.load(file));
        assertTrue(e.getMessage().contains("set.jsonl:3"), e.getMessage());
        assertTrue(e.getMessage().contains("invalid JSON"), e.getMessage());
    }

    @Test
    void missingFieldsAndDuplicatesAreRejected() {
        IllegalArgumentException noQuestion = assertThrows(IllegalArgumentException.class,
                () -> EvalDataset.parse(List.of("{\"id\":\"q1\"}"), "s"));
        assertTrue(noQuestion.getMessage().contains("s:1") && noQuestion.getMessage().contains("question"));

        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class, () -> EvalDataset.parse(List.of(
                "{\"id\":\"q1\",\"question\":\"a\"}", "{\"id\":\"q1\",\"question\":\"b\"}"), "s"));
        assertTrue(dup.getMessage().contains("s:2") && dup.getMessage().contains("duplicate"));

        assertThrows(IllegalArgumentException.class,
                () -> EvalDataset.parse(List.of("{\"id\":\"q1\",\"question\":\"a\",\"relevant\":{}}"), "s"));
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.parse(List.of("[1,2]"), "s"));
    }
}
