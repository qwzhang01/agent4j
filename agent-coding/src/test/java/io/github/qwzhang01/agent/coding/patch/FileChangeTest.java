package io.github.qwzhang01.agent.coding.patch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stage 17 M17.2: the change record's kind/content invariants - a CREATE must not
 * claim a baseline, a DELETE must not carry content.
 */
class FileChangeTest {

    @Test
    @DisplayName("CREATE: no oldContent, newContent required")
    void create() {
        FileChange c = new FileChange("New.java", FileChange.ChangeKind.CREATE, "content", null);
        assertEquals("CREATE New.java", c.describe());

        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("New.java", FileChange.ChangeKind.CREATE, "content", "baseline"));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("New.java", FileChange.ChangeKind.CREATE, null, null));
    }

    @Test
    @DisplayName("MODIFY: both sides required")
    void modify() {
        new FileChange("App.java", FileChange.ChangeKind.MODIFY, "new", "old");
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("App.java", FileChange.ChangeKind.MODIFY, "new", null));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("App.java", FileChange.ChangeKind.MODIFY, null, "old"));
    }

    @Test
    @DisplayName("DELETE: no newContent, oldContent snapshot required")
    void delete() {
        new FileChange("App.java", FileChange.ChangeKind.DELETE, null, "old");
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("App.java", FileChange.ChangeKind.DELETE, "new", "old"));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("App.java", FileChange.ChangeKind.DELETE, null, null));
    }

    @Test
    @DisplayName("blank path and null kind are rejected")
    void pathAndKindGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange(" ", FileChange.ChangeKind.CREATE, "c", null));
        assertThrows(NullPointerException.class,
                () -> new FileChange("a", null, "c", null));
    }

    @Test
    @DisplayName("Patch record guards and withers")
    void patchGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new Patch(null, java.util.List.of(), Patch.PatchStatus.DRAFT, java.time.Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Patch("P-1", null, Patch.PatchStatus.DRAFT, java.time.Instant.now()));

        Patch p = new Patch("P-1", java.util.List.of(), Patch.PatchStatus.DRAFT, java.time.Instant.now());
        assertEquals(Patch.PatchStatus.VALIDATED, p.withStatus(Patch.PatchStatus.VALIDATED).status());
        assertEquals(0, p.size());
    }
}
