package io.github.qwzhang01.agent.coding.patch;

/**
 * A single-file change staged in the {@link PatchStore} (Stage 17 M17.2, blueprint D1:
 * "a change is a first-class value - enumerable, auditable, revertable").
 * <p>
 * Kind is derived from the file system state at staging time: absent file + new content
 * = {@code CREATE}; existing file + new content = {@code MODIFY}; existing file + deletion
 * = {@code DELETE}. {@code oldContent} is the on-disk snapshot taken when the change was
 * staged - it is the <b>drift baseline</b> for {@link PatchStore#apply()} (TOCTOU defense)
 * and the "before" side of the diff rendering.
 * <p>
 * Stores the full new content (not a line-level delta) - v1 honest boundary: minimal-edit
 * (Myers) diff computation is deferred, rendering happens on demand.
 *
 * @param path       workspace-relative path (POSIX-style separators on this platform)
 * @param kind       CREATE / MODIFY / DELETE
 * @param newContent full new content; null only for DELETE
 * @param oldContent on-disk snapshot at staging time; null only for CREATE
 */
public record FileChange(String path, ChangeKind kind, String newContent, String oldContent) {

    public enum ChangeKind { CREATE, MODIFY, DELETE }

    public FileChange {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be null or blank");
        }
        switch (kind) {
            case CREATE -> {
                if (oldContent != null) {
                    throw new IllegalArgumentException("CREATE change must not carry oldContent");
                }
                if (newContent == null) {
                    throw new IllegalArgumentException("CREATE change requires newContent");
                }
            }
            case MODIFY -> {
                if (oldContent == null || newContent == null) {
                    throw new IllegalArgumentException("MODIFY change requires both oldContent and newContent");
                }
            }
            case DELETE -> {
                if (newContent != null) {
                    throw new IllegalArgumentException("DELETE change must not carry newContent");
                }
                if (oldContent == null) {
                    throw new IllegalArgumentException("DELETE change requires the oldContent snapshot");
                }
            }
        }
    }

    /** Human-readable one-liner for logs and audit lines. */
    public String describe() {
        return kind + " " + path;
    }
}
