package io.github.qwzhang01.agent.coding.patch;

import io.github.qwzhang01.agent.coding.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-scoped staging area for file changes (Stage 17 M17.2, blueprint D1:
 * "writing to disk is a privilege, the patch is the request form").
 * <p>
 * The core invariant - <b>staging never touches the disk</b>: {@link #stage} /
 * {@link #stageDeletion} only record a {@link FileChange} (with the on-disk snapshot as
 * drift baseline). The one and only write point is {@link #apply()}, and it is
 * two-phase: verify every change against its baseline first (fail whole-patch on drift),
 * then write all files. Half-applied is the worst state; the two-phase window is kept
 * minimal (v1 honest boundary: a kill mid-write can still leave a partial apply - that
 * is Stage 6 checkpoint territory, not this class's).
 * <p>
 * Semantics:
 * <ul>
 *   <li>re-staging the same path <b>replaces</b> the entry (fix-loop friendly), with a
 *       fresh on-disk snapshot as the new baseline</li>
 *   <li>staging is only allowed while the patch is DRAFT (after VALIDATED the patch is
 *       frozen for review; discard first to start over)</li>
 *   <li>drift rejection leaves the disk exactly as-is - a rejected apply has zero side
 *       effects, including not clobbering the human's concurrent edit</li>
 *   <li>{@code apply()}/{@code discard()}/{@code reject()} all close the patch; the next
 *       stage opens a fresh one with a new patchId</li>
 * </ul>
 * <p>
 * Path safety inherits the workspace layers: escape/absolute/blank rejection from
 * {@link Workspace#resolve(String)}, deny-list from the policy, and an extra rule of
 * its own - staging (or applying to) a symbolic-link path is rejected outright
 * (writing through a symlink could write outside the root).
 */
public final class PatchStore {

    private final Workspace workspace;
    private final AtomicLong patchCounter = new AtomicLong();

    private Patch current;                                   // null = no active patch
    private final Map<String, FileChange> staged = new LinkedHashMap<>();  // path -> change, first-staging order
    /**
     * Patch-level first baseline: the on-disk state of each path when it was FIRST
     * staged in this patch (empty Optional = the file did not exist). Re-stage refreshes
     * the change's {@code oldContent} drift baseline (fix-loop friendly) but never this:
     * revert must go all the way back to where the PATCH started, not where the last
     * re-stage started - otherwise a fix loop could never be fully undone.
     */
    private final Map<String, Optional<String>> firstBaselines = new LinkedHashMap<>();

    public PatchStore(Workspace workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    }

    // ============ Staging (never touches the disk) ============

    /**
     * Stage a write: create or modify {@code path} with the given full content.
     *
     * @return the staged change (kind derived from the current disk state)
     * @throws IllegalArgumentException on escape/deny/symlink path, staging onto a
     *                                  directory, or staging while not DRAFT
     */
    public FileChange stage(String path, String newContent) {
        Objects.requireNonNull(newContent,
                "newContent must not be null (use stageDeletion to delete a file)");
        return stageChange(path, newContent);
    }

    /**
     * Stage a deletion of {@code path} (must currently exist).
     */
    public FileChange stageDeletion(String path) {
        return stageChange(path, null);
    }

    private FileChange stageChange(String path, String newContent /* null = delete */) {
        if (current != null && current.status() != Patch.PatchStatus.DRAFT) {
            throw new IllegalArgumentException("cannot stage while patch " + current.patchId()
                    + " is " + current.status() + " - discard it first to start a new patch");
        }
        Path abs = workspace.resolve(path);
        Path rel = workspace.root().relativize(abs);
        String relPath = rel.toString();

        if (workspace.policy().isDenied(rel)) {
            throw new IllegalArgumentException("path is denied by workspace policy: " + relPath);
        }
        if (Files.isSymbolicLink(abs)) {
            throw new IllegalArgumentException("cannot stage a symbolic-link path: " + relPath);
        }
        if (Files.isDirectory(abs)) {
            throw new IllegalArgumentException("cannot stage a directory: " + relPath);
        }

        boolean exists = Files.isRegularFile(abs);
        String oldContent = exists ? readForSnapshot(abs, relPath) : null;
        // freeze the patch's first baseline for this path (re-stages never overwrite it)
        firstBaselines.computeIfAbsent(relPath, k -> Optional.ofNullable(oldContent));

        FileChange.ChangeKind kind;
        if (newContent == null) {
            if (!exists) {
                throw new IllegalArgumentException("cannot delete a file that does not exist: " + relPath);
            }
            kind = FileChange.ChangeKind.DELETE;
        } else {
            kind = exists ? FileChange.ChangeKind.MODIFY : FileChange.ChangeKind.CREATE;
        }

        FileChange change = new FileChange(relPath, kind, newContent, oldContent);
        staged.put(relPath, change);                            // same path -> replace
        if (current == null) {
            current = new Patch(nextPatchId(), List.copyOf(staged.values()),
                    Patch.PatchStatus.DRAFT, Instant.now());
        } else {
            current = current.withChanges(List.copyOf(staged.values()));
        }
        return change;
    }

    private String readForSnapshot(Path abs, String relPath) {
        try {
            return Files.readString(abs, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "cannot read current content of " + relPath + ": " + e.getMessage(), e);
        }
    }

    // ============ State machine ============

    /** The active patch (DRAFT or VALIDATED), if any. */
    public Optional<Patch> snapshot() {
        return Optional.ofNullable(current);
    }

    /**
     * DRAFT -> VALIDATED. Wired to the test-passed signal by {@code CodingSession} (M17.4);
     * the mechanism lives here so the state machine has one home.
     */
    public void markValidated() {
        ensureActive("markValidated");
        if (current.status() != Patch.PatchStatus.DRAFT) {
            throw new IllegalArgumentException(
                    "only a DRAFT patch can be validated, current status: " + current.status());
        }
        current = current.withStatus(Patch.PatchStatus.VALIDATED);
    }

    /**
     * The one and only disk-write point: verify all baselines, then write all files.
     * Allowed from DRAFT (untested but human-approved) or VALIDATED.
     * <p>
     * A change whose disk content already equals its {@code newContent} (a prior
     * {@link #materialize()}) is applied idempotently - see {@link OnDiskState}.
     * <p>
     * On drift: nothing is written, the disk keeps its current content.
     */
    public ApplyResult apply() {
        ensureActive("apply");

        // ---- phase 1: verify every change (fail whole-patch on first drift) ----
        for (FileChange change : current.changes()) {
            if (onDiskState(change) == OnDiskState.DRIFT) {
                return driftResult(change);
            }
        }

        // ---- phase 2: write all files (skip already-materialized changes) ----
        int written = 0;
        for (FileChange change : current.changes()) {
            if (onDiskState(change) == OnDiskState.BASELINE) {
                writeToDisk(change);
            }
            written++;
        }

        Patch applied = current.withStatus(Patch.PatchStatus.APPLIED);
        close();
        return new ApplyResult.Applied(applied, written);
    }

    /**
     * Write the staged changes to disk so the test referee can see them (blueprint T3's
     * hidden premise, surfaced and honored at assembly time: {@code run_tests} executes
     * against the real workspace, and the workspace must reflect the patch under test).
     * <p>
     * Idempotent per change: BASELINE gets written, MATERIALIZED is a no-op, DRIFT
     * fails loud. The fix loop closes naturally on this: re-staging snapshots the
     * materialized disk as the new baseline, so the next materialize is a normal
     * BASELINE write again.
     *
     * @return true if anything was written, false if everything was already materialized
     * @throws IllegalStateException on drift (the workspace was hand-edited in between)
     */
    public boolean materialize() {
        ensureActive("materialize");
        for (FileChange change : current.changes()) {
            if (onDiskState(change) == OnDiskState.DRIFT) {
                throw new IllegalStateException("cannot materialize, drift detected on "
                        + change.describe() + ": " + driftReason(change));
            }
        }
        boolean wrote = false;
        for (FileChange change : current.changes()) {
            if (onDiskState(change) == OnDiskState.BASELINE) {
                writeToDisk(change);
                wrote = true;
            }
        }
        return wrote;
    }

    /**
     * Restore the disk to the state it had when this <b>patch</b> began - undo every
     * materialize and every intermediate fix-loop round. Allowed disk states per change:
     * the first baseline (already restored / never touched), the last re-stage snapshot
     * (an earlier materialize's leftover), or the current staged content (just
     * materialized). Anything else is drift and fails loud - a hand-edited workspace is
     * not automatically overwritten (human sovereignty over machine bookkeeping).
     *
     * @throws IllegalStateException on drift
     */
    public void revert() {
        ensureActive("revert");
        for (FileChange change : current.changes()) {
            verifyRevertable(change);
        }
        for (FileChange change : current.changes()) {
            restoreFirstBaseline(change);
        }
    }

    // ============ Internals ============

    /** Where the disk stands relative to one staged change. */
    private enum OnDiskState {
        /** Disk matches the staging-time snapshot (the change is NOT on disk yet). */
        BASELINE,
        /** Disk matches the new content (a materialize wrote it). */
        MATERIALIZED,
        /** Anything else: someone touched the disk in between. */
        DRIFT
    }

    private OnDiskState onDiskState(FileChange change) {
        Path abs = workspace.root().resolve(change.path());
        if (Files.isSymbolicLink(abs)) {
            return OnDiskState.DRIFT;
        }
        switch (change.kind()) {
            case CREATE -> {
                if (!Files.exists(abs)) {
                    return OnDiskState.BASELINE;
                }
                return contentEquals(abs, change.newContent())
                        ? OnDiskState.MATERIALIZED : OnDiskState.DRIFT;
            }
            case MODIFY -> {
                if (!Files.isRegularFile(abs)) {
                    return OnDiskState.DRIFT;
                }
                if (contentEquals(abs, change.oldContent())) {
                    return OnDiskState.BASELINE;
                }
                return contentEquals(abs, change.newContent())
                        ? OnDiskState.MATERIALIZED : OnDiskState.DRIFT;
            }
            case DELETE -> {
                if (!Files.exists(abs)) {
                    return OnDiskState.MATERIALIZED;   // deletion already performed
                }
                if (!Files.isRegularFile(abs)) {
                    return OnDiskState.DRIFT;
                }
                return contentEquals(abs, change.oldContent())
                        ? OnDiskState.BASELINE : OnDiskState.DRIFT;
            }
        }
        return OnDiskState.DRIFT;
    }

    private boolean contentEquals(Path abs, String expected) {
        try {
            return Files.readString(abs, StandardCharsets.UTF_8).equals(expected);
        } catch (IOException e) {
            return false;
        }
    }

    private ApplyResult driftResult(FileChange change) {
        return new ApplyResult.DriftRejected(change.path(), driftReason(change));
    }

    private String driftReason(FileChange change) {
        Path abs = workspace.root().resolve(change.path());
        if (Files.isSymbolicLink(abs)) {
            return "path became a symbolic link since staging";
        }
        return switch (onDiskState(change)) {
            case DRIFT -> "content changed on disk since staging: matches neither the "
                    + "staging snapshot nor the staged content (hand edit in between?)";
            default -> "unexpected state";
        };
    }

    /** Close the patch as DISCARDED; the disk is untouched. Returns the terminal patch. */
    public Patch discard() {
        ensureActive("discard");
        Patch discarded = current.withStatus(Patch.PatchStatus.DISCARDED);
        close();
        return discarded;
    }

    /** Close the patch as REJECTED (human gate); the disk is untouched. Returns the terminal patch. */
    public Patch reject() {
        ensureActive("reject");
        Patch rejected = current.withStatus(Patch.PatchStatus.REJECTED);
        close();
        return rejected;
    }

    // ============ Internals ============

    private void writeToDisk(FileChange change) {
        Path abs = workspace.root().resolve(change.path());
        try {
            switch (change.kind()) {
                case CREATE, MODIFY -> {
                    Path parent = abs.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(abs, change.newContent(), StandardCharsets.UTF_8);
                }
                case DELETE -> Files.delete(abs);
            }
        } catch (IOException e) {
            // fail loud; v1 honest boundary: files written before this point stay written
            // (the two-phase check above minimizes, but cannot eliminate, this window)
            throw new IllegalStateException("failed to apply change " + change.describe()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * A change is revertable when the disk shows one of the states this patch's own
     * lifecycle could have produced: the first baseline (patch start), the staged
     * content (just materialized), the last re-stage snapshot (an earlier fix round's
     * materialized leftover), or - for DELETE - plain absence (deleted by materialize).
     * Anything else is a hand edit in between: drift, fail loud.
     */
    private void verifyRevertable(FileChange change) {
        Path abs = workspace.root().resolve(change.path());
        if (Files.isSymbolicLink(abs)) {
            throw new IllegalStateException("cannot revert " + change.describe()
                    + ": path became a symbolic link");
        }
        Optional<String> first = firstBaselines.getOrDefault(change.path(), Optional.empty());
        if (diskMatches(abs, first)) {
            return;   // already at the patch-start state
        }
        if (change.newContent() != null && contentEquals(abs, change.newContent())) {
            return;   // just materialized
        }
        if (change.oldContent() != null && contentEquals(abs, change.oldContent())) {
            return;   // an earlier fix round's materialized leftover
        }
        if (change.newContent() == null && !Files.exists(abs)) {
            return;   // DELETE materialized (file gone)
        }
        throw new IllegalStateException("cannot revert, drift detected on "
                + change.describe() + ": " + driftReason(change));
    }

    private boolean diskMatches(Path abs, Optional<String> expected) {
        if (expected.isEmpty()) {
            return !Files.exists(abs);
        }
        return contentEquals(abs, expected.get());
    }

    /** Put the first baseline back: CREATE deletes, MODIFY/DELETE restore the original content. */
    private void restoreFirstBaseline(FileChange change) {
        Path abs = workspace.root().resolve(change.path());
        Optional<String> first = firstBaselines.getOrDefault(change.path(), Optional.empty());
        if (diskMatches(abs, first)) {
            return;   // already at the patch-start state
        }
        try {
            if (first.isEmpty()) {
                Files.deleteIfExists(abs);
            } else {
                Path parent = abs.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(abs, first.get(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to revert change " + change.describe()
                    + ": " + e.getMessage(), e);
        }
    }

    private void ensureActive(String action) {
        if (current == null) {
            throw new IllegalArgumentException(
                    "no active patch - stage something before calling " + action + "()");
        }
    }

    private void close() {
        current = null;
        staged.clear();
        firstBaselines.clear();
    }

    private String nextPatchId() {
        return "P-" + patchCounter.incrementAndGet();
    }
}
