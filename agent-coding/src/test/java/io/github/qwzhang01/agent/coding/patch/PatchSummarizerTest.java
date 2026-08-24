package io.github.qwzhang01.agent.coding.patch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.2: the human-review rendering - common prefix/suffix diff (v1 honest
 * boundary: not a minimal edit script), CREATE/DELETE via /dev/null, and honest line
 * counts (a re-staged file with no textual change shows +0 -0).
 */
class PatchSummarizerTest {

    private final PatchSummarizer summarizer = new PatchSummarizer();

    @Test
    @DisplayName("MODIFY diff: unchanged head/tail trimmed, middle rendered as -old +new")
    void modifyDiff() {
        // newContent = the intended NEW file; oldContent = the staged-time snapshot
        FileChange change = new FileChange("App.java", FileChange.ChangeKind.MODIFY,
                "keep1\nnew-a\nkeep2\n",
                "keep1\nold-a\nold-b\nkeep2\n");

        String diff = summarizer.diffOf(change);

        assertTrue(diff.startsWith("--- App.java\n+++ App.java\n"), diff);
        assertTrue(diff.contains("@@ -2,2 +2,1 @@"), diff);
        assertTrue(diff.contains("-old-a\n-"), diff);
        assertTrue(diff.contains("+new-a\n"), diff);
        assertTrue(!diff.contains("-keep"), "context lines are not marked as removed: " + diff);
    }

    @Test
    @DisplayName("CREATE diff: all lines added, old side is /dev/null")
    void createDiff() {
        FileChange change = new FileChange("New.java", FileChange.ChangeKind.CREATE,
                "a\nb\n", null);

        String diff = summarizer.diffOf(change);

        assertTrue(diff.contains("--- /dev/null"), diff);
        assertTrue(diff.contains("+++ New.java"), diff);
        assertTrue(diff.contains("+a\n+b\n"), diff);
        assertEquals(0, summarizer.removedLines(change), "CREATE has no removed lines");
    }

    @Test
    @DisplayName("DELETE diff: all lines removed, new side is /dev/null")
    void deleteDiff() {
        FileChange change = new FileChange("Old.java", FileChange.ChangeKind.DELETE,
                null, "a\nb\n");

        String diff = summarizer.diffOf(change);

        assertTrue(diff.contains("--- Old.java"), diff);
        assertTrue(diff.contains("+++ /dev/null"), diff);
        assertTrue(diff.contains("-a\n-b\n"), diff);
        assertEquals(0, summarizer.addedLines(change), "DELETE has no added lines");
    }

    @Test
    @DisplayName("MODIFY with identical content renders 'no textual change' and +0 -0")
    void noChange() {
        FileChange change = new FileChange("Same.java", FileChange.ChangeKind.MODIFY,
                "a\nb\n", "a\nb\n");

        String diff = summarizer.diffOf(change);
        assertTrue(diff.contains("(no textual change)"), diff);

        assertEquals(0, summarizer.addedLines(change));
        assertEquals(0, summarizer.removedLines(change));
    }

    @Test
    @DisplayName("line counts: middle blocks only, head/tail not counted")
    void lineCounts() {
        // old: h1 h2 [x y z] t1 t2   ->  new: h1 h2 [a] t1 t2
        FileChange change = new FileChange("App.java", FileChange.ChangeKind.MODIFY,
                "h1\nh2\na\nt1\nt2\n",
                "h1\nh2\nx\ny\nz\nt1\nt2\n");

        assertEquals(1, summarizer.addedLines(change), "+a only");
        assertEquals(3, summarizer.removedLines(change), "-x -y -z only");
    }

    @Test
    @DisplayName("summarize: header carries file-kind counts and total line deltas")
    void summarize() {
        List<FileChange> changes = List.of(
                new FileChange("A.java", FileChange.ChangeKind.MODIFY, "a\nb\nc\n", "a\nX\nc\n"),
                new FileChange("B.java", FileChange.ChangeKind.CREATE, "n1\nn2\n", null),
                new FileChange("C.java", FileChange.ChangeKind.DELETE, null, "d1\nd2\nd3\nd4\n"));
        Patch patch = new Patch("P-1", changes, Patch.PatchStatus.VALIDATED, Instant.now());

        String summary = summarizer.summarize(patch);

        // +: A(+1) + B(+2) = 3;  -: A(-1) + C(-4) = 5
        assertTrue(summary.contains("Patch P-1 [VALIDATED]: 3 file(s) - 1 create, 1 modify, 1 delete (+3 -5 lines)"),
                summary);
        assertTrue(summary.contains("--- A.java"), summary);
        assertTrue(summary.contains("--- /dev/null"), "CREATE or DELETE side: " + summary);
    }

    @Test
    @DisplayName("empty file contents produce zero lines, not a phantom empty line")
    void emptyContentLines() {
        FileChange create = new FileChange("E.java", FileChange.ChangeKind.CREATE, "", null);
        assertEquals(0, summarizer.addedLines(create));

        FileChange modify = new FileChange("E.java", FileChange.ChangeKind.MODIFY, "", "");
        String diff = summarizer.diffOf(modify);
        assertTrue(diff.contains("(no textual change)"), diff);
    }
}
