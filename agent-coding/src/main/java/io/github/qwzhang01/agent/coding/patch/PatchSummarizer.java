package io.github.qwzhang01.agent.coding.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link Patch} for human review (Stage 17 M17.2): a unified-diff-style block
 * per file plus a one-line summary - this is what the reviewer reads before approving
 * {@link PatchStore#apply()}.
 * <p>
 * Diff algorithm (v1 honest boundary): common-prefix / common-suffix trim, the middle
 * is one remove-block + one add-block. This is NOT a minimal edit script (Myers diff
 * is v2 scope) - but for review purposes it shows exactly what changed, with honest
 * line counts. Hunks carry no context lines in v1.
 * <p>
 * Line counts: {@code +added -removed} counts the middle blocks only (a re-staged file
 * that ends up identical shows +0 -0 and an explicit "no textual change" marker).
 */
public final class PatchSummarizer {

    /** One-line summary: file counts by kind plus added/removed line counts. */
    public String summarize(Patch patch) {
        int create = 0;
        int modify = 0;
        int delete = 0;
        int added = 0;
        int removed = 0;
        for (FileChange change : patch.changes()) {
            switch (change.kind()) {
                case CREATE -> create++;
                case MODIFY -> modify++;
                case DELETE -> delete++;
            }
            added += addedLines(change);
            removed += removedLines(change);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Patch ").append(patch.patchId()).append(" [").append(patch.status()).append("]: ")
                .append(patch.size()).append(" file(s) - ")
                .append(create).append(" create, ")
                .append(modify).append(" modify, ")
                .append(delete).append(" delete")
                .append(" (+").append(added).append(" -").append(removed).append(" lines)")
                .append('\n');
        for (FileChange change : patch.changes()) {
            sb.append('\n').append(diffOf(change));
        }
        return sb.toString();
    }

    /** Unified-diff-style block for one change. */
    public String diffOf(FileChange change) {
        String oldPath = change.kind() == FileChange.ChangeKind.CREATE
                ? "/dev/null" : change.path();
        String newPath = change.kind() == FileChange.ChangeKind.DELETE
                ? "/dev/null" : change.path();

        List<String> oldLines = change.oldContent() == null ? List.of() : lines(change.oldContent());
        List<String> newLines = change.newContent() == null ? List.of() : lines(change.newContent());

        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);

        int removedMid = oldLines.size() - prefix - suffix;
        int addedMid = newLines.size() - prefix - suffix;

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(oldPath).append('\n');
        sb.append("+++ ").append(newPath).append('\n');

        if (removedMid == 0 && addedMid == 0) {
            sb.append("(no textual change)").append('\n');
            return sb.toString();
        }

        sb.append("@@ -").append(prefix + 1).append(',').append(removedMid)
                .append(" +").append(prefix + 1).append(',').append(addedMid)
                .append(" @@\n");
        for (int i = prefix; i < oldLines.size() - suffix; i++) {
            sb.append('-').append(oldLines.get(i)).append('\n');
        }
        for (int i = prefix; i < newLines.size() - suffix; i++) {
            sb.append('+').append(newLines.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** Number of lines the change adds (the + side of its diff). */
    public int addedLines(FileChange change) {
        if (change.newContent() == null) {
            return 0;
        }
        if (change.oldContent() == null) {
            return lines(change.newContent()).size();
        }
        List<String> oldLines = lines(change.oldContent());
        List<String> newLines = lines(change.newContent());
        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);
        return newLines.size() - prefix - suffix;
    }

    /** Number of lines the change removes (the - side of its diff). */
    public int removedLines(FileChange change) {
        if (change.oldContent() == null) {
            return 0;
        }
        if (change.newContent() == null) {
            return lines(change.oldContent()).size();
        }
        List<String> oldLines = lines(change.oldContent());
        List<String> newLines = lines(change.newContent());
        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);
        return oldLines.size() - prefix - suffix;
    }

    // ============ Internals ============

    private static int commonPrefix(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return i;
            }
        }
        return n;
    }

    private static int commonSuffix(List<String> a, List<String> b, int prefix) {
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        return suffix;
    }

    /** Split into lines; a trailing newline does not produce a phantom empty line. */
    private static List<String> lines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(List.of(content.split("\n", -1)));
        // "a\nb\n".split("\n", -1) == [a, b, ""] - drop the phantom trailing empty line,
        // but keep interior empty lines ("a\n\nb\n" stays [a, "", b])
        if (result.size() > 1 && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }
}
