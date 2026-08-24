package io.github.qwzhang01.agent.coding.patch;

import io.github.qwzhang01.agent.coding.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.2: the staging area - "staging never touches the disk" is the invariant
 * threaded through every test here, and apply is the single write point with
 * whole-patch drift detection (TOCTOU defense).
 */
class PatchStoreTest {

    @TempDir
    Path tempDir;

    private Path root;
    private Workspace workspace;
    private PatchStore store;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(root.resolve("App.java"), "line1\nline2\nline3\n");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[core]");
        workspace = Workspace.open(root);
        store = new PatchStore(workspace);
    }

    // ============ Staging: never touches the disk ============

    @Test
    @DisplayName("staging N times (incl. re-staging) leaves the disk byte-identical")
    void stagingNeverWrites() throws IOException {
        Map<Path, byte[]> before = diskSnapshot();

        store.stage("App.java", "changed content");
        store.stage("New.java", "brand new");
        store.stage("App.java", "changed again");
        store.stageDeletion("App.java");
        store.stage("App.java", "restored intent");

        Map<Path, byte[]> after = diskSnapshot();
        assertEquals(before.keySet(), after.keySet(), "staging must not create or delete files");
        for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
            assertTrue(java.util.Arrays.equals(entry.getValue(), after.get(entry.getKey())),
                    "staging must not touch a single byte of " + entry.getKey());
        }
    }

    @Test
    @DisplayName("re-staging the same path replaces the entry, not stacks it")
    void reStageReplaces() {
        store.stage("App.java", "v2");
        store.stage("App.java", "v3");
        store.stage("New.java", "n1");

        Optional<Patch> patch = store.snapshot();
        assertTrue(patch.isPresent());
        assertEquals(2, patch.get().size(), "App.java counts once (replaced) + New.java");

        FileChange app = patch.get().changes().get(0);
        assertEquals(FileChange.ChangeKind.MODIFY, app.kind());
        assertEquals("v3", app.newContent(), "latest staged content wins");
        assertEquals("line1\nline2\nline3\n", app.oldContent(), "baseline is the on-disk snapshot");
    }

    @Test
    @DisplayName("kind derivation: absent file = CREATE, existing = MODIFY, deletion of missing = rejected")
    void kindDerivation() {
        assertEquals(FileChange.ChangeKind.MODIFY, store.stage("App.java", "x").kind());
        assertEquals(FileChange.ChangeKind.CREATE, store.stage("New.java", "x").kind());
        assertEquals(FileChange.ChangeKind.DELETE, store.stageDeletion("App.java").kind());
        assertThrows(IllegalArgumentException.class, () -> store.stageDeletion("Missing.java"));
        assertThrows(NullPointerException.class, () -> store.stage("App.java", null));
    }

    @Test
    @DisplayName("escape / deny / symlink / directory paths cannot be staged")
    void stagingPathSafety() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> store.stage("../outside.txt", "x"));
        assertThrows(IllegalArgumentException.class, () -> store.stage("/etc/passwd", "x"));
        assertThrows(IllegalArgumentException.class, () -> store.stage(".git/config", "x"));
        assertThrows(IllegalArgumentException.class, () -> store.stage(".env", "x"));
        assertThrows(IllegalArgumentException.class, () -> store.stage("src", "x"));

        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "x");
        Files.createSymbolicLink(root.resolve("link.txt"), outside);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.stage("link.txt", "x"));
        assertTrue(e.getMessage().contains("symbolic"), e.getMessage());
    }

    // ============ Apply: the single write point ============

    @Test
    @DisplayName("apply round-trip: CREATE writes, MODIFY overwrites, DELETE removes - as one batch")
    void applyRoundTrip() throws IOException {
        store.stage("App.java", "line1\nline2-modified\nline3\n");
        store.stage("src/deep/New.java", "class New {}");
        store.stageDeletion("App.java");   // replaced? no - deletion re-stages the same path

        // careful: the third call REPLACES the modify entry for App.java
        Optional<Patch> before = store.snapshot();
        assertEquals(2, before.get().size());

        ApplyResult result = store.apply();

        assertInstanceOf(ApplyResult.Applied.class, result);
        ApplyResult.Applied applied = (ApplyResult.Applied) result;
        assertEquals(2, applied.filesWritten());
        assertEquals(Patch.PatchStatus.APPLIED, applied.patch().status());
        assertFalse(store.snapshot().isPresent(), "patch closed after apply");
        assertFalse(Files.exists(root.resolve("App.java")), "DELETE removed the file");
        assertEquals("class New {}", Files.readString(root.resolve("src/deep/New.java")));
    }

    @Test
    @DisplayName("apply creates missing parent directories (CREATE in a deep path)")
    void applyCreatesParents() throws IOException {
        store.stage("a/b/c/New.java", "content");
        store.apply();

        assertEquals("content", Files.readString(root.resolve("a/b/c/New.java")));
    }

    @Test
    @DisplayName("next stage after apply opens a fresh patch with a new patchId")
    void freshPatchAfterClose() {
        store.stage("A.java", "a");
        store.apply();

        store.stage("B.java", "b");
        Patch second = store.snapshot().orElseThrow();
        assertNotEquals("P-1", second.patchId());
        assertEquals("P-2", second.patchId());
        assertEquals(Patch.PatchStatus.DRAFT, second.status());
    }

    // ============ Drift detection (TOCTOU) ============

    @Test
    @DisplayName("drift: file hand-edited between staging and apply -> whole-patch rejection, disk keeps the edit")
    void driftOnModify() throws IOException {
        store.stage("App.java", "staged content");
        store.stage("New.java", "new content");

        // a human edits the file while the patch awaits approval
        Files.writeString(root.resolve("App.java"), "human edit");

        ApplyResult result = store.apply();

        assertInstanceOf(ApplyResult.DriftRejected.class, result);
        ApplyResult.DriftRejected drift = (ApplyResult.DriftRejected) result;
        assertEquals("App.java", drift.path());
        assertTrue(drift.reason().contains("changed on disk"), drift.reason());

        // zero side effects: the human edit survives, the CREATE never happened
        assertEquals("human edit", Files.readString(root.resolve("App.java")));
        assertFalse(Files.exists(root.resolve("New.java")), "a drifted apply must not half-apply");
    }

    @Test
    @DisplayName("drift: CREATE collides with a file that appeared on disk")
    void driftOnCreate() throws IOException {
        store.stage("New.java", "staged");
        Files.writeString(root.resolve("New.java"), "appeared meanwhile");

        ApplyResult result = store.apply();

        assertInstanceOf(ApplyResult.DriftRejected.class, result);
        assertEquals("New.java", ((ApplyResult.DriftRejected) result).path());
        assertEquals("appeared meanwhile", Files.readString(root.resolve("New.java")));
    }

    @Test
    @DisplayName("drift: staged file deleted on disk since staging")
    void driftOnDisappearedFile() throws IOException {
        store.stage("App.java", "staged");
        Files.delete(root.resolve("App.java"));

        ApplyResult result = store.apply();

        assertInstanceOf(ApplyResult.DriftRejected.class, result);
        assertFalse(Files.exists(root.resolve("App.java")));
    }

    @Test
    @DisplayName("drift: path replaced by a symbolic link since staging")
    void driftOnSymlink() throws IOException {
        store.stage("New.java", "staged");
        Path outside = Files.writeString(tempDir.resolve("evil.txt"), "x");
        Files.createSymbolicLink(root.resolve("New.java"), outside);

        ApplyResult result = store.apply();

        assertInstanceOf(ApplyResult.DriftRejected.class, result);
        assertTrue(((ApplyResult.DriftRejected) result).reason().contains("symbolic"),
                ((ApplyResult.DriftRejected) result).reason());
    }

    // ============ State machine ============

    @Test
    @DisplayName("discard: disk untouched, terminal state DISCARDED, store reopens fresh")
    void discardKeepsDiskClean() throws IOException {
        store.stage("App.java", "would-be change");
        store.stage("New.java", "never created");

        Patch discarded = store.discard();

        assertEquals(Patch.PatchStatus.DISCARDED, discarded.status());
        assertFalse(store.snapshot().isPresent());
        assertEquals("line1\nline2\nline3\n", Files.readString(root.resolve("App.java")));
        assertFalse(Files.exists(root.resolve("New.java")));
    }

    @Test
    @DisplayName("reject: human-gate terminal state, disk untouched")
    void rejectKeepsDiskClean() throws IOException {
        store.stage("App.java", "would-be change");

        Patch rejected = store.reject();

        assertEquals(Patch.PatchStatus.REJECTED, rejected.status());
        assertEquals("line1\nline2\nline3\n", Files.readString(root.resolve("App.java")));
    }

    @Test
    @DisplayName("markValidated: DRAFT -> VALIDATED; staging after validation is rejected (discard first)")
    void validationFreezesStaging() {
        store.stage("App.java", "change");
        store.markValidated();

        assertEquals(Patch.PatchStatus.VALIDATED, store.snapshot().orElseThrow().status());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.stage("Other.java", "x"));
        assertTrue(e.getMessage().contains("discard"), e.getMessage());
    }

    @Test
    @DisplayName("apply is allowed straight from DRAFT (human gate may approve untested)")
    void applyFromDraftAllowed() throws IOException {
        store.stage("App.java", "change");

        ApplyResult result = store.apply();

        assertInstanceOf(ApplyResult.Applied.class, result);
        assertEquals("change", Files.readString(root.resolve("App.java")), "MODIFY overwrites in place");
    }

    @Test
    @DisplayName("empty-store guards: apply/discard/reject/markValidated all fail fast")
    void emptyStoreGuards() {
        assertThrows(IllegalArgumentException.class, () -> store.apply());
        assertThrows(IllegalArgumentException.class, () -> store.discard());
        assertThrows(IllegalArgumentException.class, () -> store.reject());
        assertThrows(IllegalArgumentException.class, () -> store.markValidated());
    }

    @Test
    @DisplayName("double close is rejected: no active patch anymore")
    void doubleCloseRejected() {
        store.stage("App.java", "x");
        store.discard();
        assertThrows(IllegalArgumentException.class, () -> store.discard());
    }

    // ============ Materialize / revert (blueprint T3's hidden premise) ============

    @Test
    @DisplayName("materialize writes the staged changes so a test command can see them")
    void materializeWrites() throws IOException {
        store.stage("App.java", "materialized content");
        store.stage("New.java", "created by materialize");

        assertTrue(store.materialize());
        assertEquals("materialized content", Files.readString(root.resolve("App.java")));
        assertEquals("created by materialize", Files.readString(root.resolve("New.java")));
        // the patch is still active and still DRAFT
        assertEquals(Patch.PatchStatus.DRAFT, store.snapshot().orElseThrow().status());
    }

    @Test
    @DisplayName("materialize is idempotent: a second call writes nothing")
    void materializeIdempotent() throws IOException {
        store.stage("App.java", "v1");
        assertTrue(store.materialize());
        assertFalse(store.materialize());
        assertEquals("v1", Files.readString(root.resolve("App.java")));
    }

    @Test
    @DisplayName("revert restores the pre-staging state after a materialize")
    void revertRestores() throws IOException {
        store.stage("App.java", "new version");
        store.stageDeletion("App.java");   // final intent: delete (replaces the modify)

        store.materialize();
        assertFalse(Files.exists(root.resolve("App.java")), "materialized DELETE removed it");

        store.revert();
        assertEquals("line1\nline2\nline3\n", Files.readString(root.resolve("App.java")),
                "revert brought the original content back");
    }

    @Test
    @DisplayName("fix-loop closure: stage v1 -> materialize -> stage v2 -> materialize -> revert goes all the way back")
    void fixLoopMaterializeClosure() throws IOException {
        store.stage("App.java", "v1");
        store.materialize();

        // the fix: re-stage snapshots the materialized disk as the new baseline
        store.stage("App.java", "v2");
        assertEquals("v1", store.snapshot().orElseThrow().changes().get(0).oldContent());
        store.materialize();
        assertEquals("v2", Files.readString(root.resolve("App.java")));

        // revert undoes the whole chain back to the original baseline
        store.revert();
        assertEquals("line1\nline2\nline3\n", Files.readString(root.resolve("App.java")));
    }

    @Test
    @DisplayName("apply after materialize is idempotent: disk already equals the patch")
    void applyAfterMaterialize() throws IOException {
        store.stage("App.java", "final content");
        store.materialize();

        ApplyResult result = store.apply();

        assertInstanceOf(ApplyResult.Applied.class, result);
        assertEquals("final content", Files.readString(root.resolve("App.java")));
    }

    @Test
    @DisplayName("a hand edit between materialize and revert fails loud (human sovereignty)")
    void materializeDriftFailsLoud() throws IOException {
        store.stage("App.java", "v1");
        store.materialize();

        Files.writeString(root.resolve("App.java"), "human edit");

        assertThrows(IllegalStateException.class, () -> store.materialize());
        assertThrows(IllegalStateException.class, () -> store.revert());
        assertEquals("human edit", Files.readString(root.resolve("App.java")));
    }

    // ============ Helpers ============

    private Map<Path, byte[]> diskSnapshot() throws IOException {
        Map<Path, byte[]> snapshot = new java.util.LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                snapshot.put(root.relativize(p), Files.readAllBytes(p));
            }
        }
        return snapshot;
    }
}
