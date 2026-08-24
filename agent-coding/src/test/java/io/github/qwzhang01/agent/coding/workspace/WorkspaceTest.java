package io.github.qwzhang01.agent.coding.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.1: the workspace view + boundary.
 * <p>
 * Path safety has the highest priority (blueprint test strategy): the three escape
 * forms (blank / absolute / {@code ../} chain) must all fail, and a symlink must not
 * smuggle content from outside the root. Deny policy, byte budget and tree budgets
 * are exercised end-to-end through the real file system.
 */
class WorkspaceTest {

    @TempDir
    Path tempDir;

    private Path root;
    private Workspace workspace;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("project"));
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("src/main/java/demo/Calculator.java"),
                "public class Calculator {\n    public static int add(int a, int b) { return a + b; }\n}\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[core]\n");
        Files.writeString(root.resolve(".env"), "SECRET=1");
        workspace = Workspace.open(root);
    }

    // ============ open ============

    @Test
    @DisplayName("open rejects a non-existent root and a root that is a regular file")
    void openValidation() throws IOException {
        Path missing = tempDir.resolve("missing");
        assertThrows(IllegalArgumentException.class, () -> Workspace.open(missing));

        Path file = Files.writeString(tempDir.resolve("file.txt"), "x");
        assertThrows(IllegalArgumentException.class, () -> Workspace.open(file));

        assertThrows(NullPointerException.class, () -> Workspace.open(root, null));
    }

    // ============ resolve: the three escape forms (highest priority) ============

    @Test
    @DisplayName("resolve: normal paths round-trip under the root, redundant segments normalize")
    void resolveNormalPaths() {
        assertEquals(root.resolve("src/main/java/demo/Calculator.java").normalize(),
                workspace.resolve("src/main/java/demo/Calculator.java"));
        assertEquals(root.resolve("pom.xml"), workspace.resolve("./pom.xml"));
        assertEquals(root.resolve("pom.xml"), workspace.resolve("src/../pom.xml"));
    }

    @Test
    @DisplayName("resolve: blank/null paths are rejected")
    void resolveBlankRejected() {
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve(""));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("   "));
    }

    @Test
    @DisplayName("resolve: absolute paths are rejected")
    void resolveAbsoluteRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> workspace.resolve("/etc/passwd"));
        assertTrue(e.getMessage().contains("absolute"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve(tempDir.toString()));
    }

    @Test
    @DisplayName("resolve: ../ chains that escape the root are rejected")
    void resolveEscapeRejected() {
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("src/../../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("a/../../.."));
    }

    // ============ readFile: deny policy (reading is a privilege) ============

    @Test
    @DisplayName("readFile: default deny set blocks .git internals, .env secrets and keys")
    void readFileDenyDefaults() throws IOException {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/server.key"), "-----BEGIN KEY-----");

        assertDenied(".git/config");
        assertDenied(".env");
        Files.writeString(root.resolve(".env.local"), "A=1");
        assertDenied(".env.local");
        assertDenied("config/server.key");
    }

    @Test
    @DisplayName("readFile: normal files come back verbatim; empty file is honestly marked")
    void readFileNormal() throws IOException {
        String content = workspace.readFile("src/main/java/demo/Calculator.java");
        assertTrue(content.contains("public class Calculator"));

        Files.writeString(root.resolve("empty.txt"), "");
        assertEquals("(empty file)", workspace.readFile("empty.txt"));
    }

    @Test
    @DisplayName("readFile: missing file / directory path are rejected with the path in the message")
    void readFileMissingAndDirectory() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> workspace.readFile("nope.txt"));
        assertTrue(missing.getMessage().contains("nope.txt"), missing.getMessage());

        assertThrows(IllegalArgumentException.class, () -> workspace.readFile("src"));
    }

    @Test
    @DisplayName("readFile: files above the byte cap are truncated with an explicit marker")
    void readFileTruncation() throws IOException {
        String big = "a".repeat(200);
        Files.writeString(root.resolve("big.txt"), big);
        Workspace limited = Workspace.open(root, WorkspacePolicy.builder()
                .maxFileBytes(50).build());

        String result = limited.readFile("big.txt");
        assertTrue(result.contains("[TRUNCATED: showing 50 of 200 bytes]"), result);
        assertEquals(50, result.substring(0, result.indexOf('\n')).length());
        assertTrue(result.startsWith("a".repeat(50)));
    }

    @Test
    @DisplayName("readFile: a symlink pointing outside the root cannot smuggle content in")
    void readFileSymlinkEscape() throws IOException {
        Path outside = Files.writeString(tempDir.resolve("outside-secret.txt"), "TOP SECRET");
        Files.createSymbolicLink(root.resolve("shortcut.txt"), outside);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> workspace.readFile("shortcut.txt"));
        assertTrue(e.getMessage().contains("symbolic link escapes"), e.getMessage());
    }

    // ============ listTree: deterministic, policy-filtered, budgeted ============

    @Test
    @DisplayName("listTree: sorted one-path-per-line output, directories suffixed with '/'")
    void listTreeRendering() {
        String tree = workspace.listTree(null, 4);
        String[] lines = tree.split("\n");

        // sorted: .env/.git/.git... are filtered, pom.xml < src/
        assertEquals("pom.xml", lines[0]);
        assertEquals("src/", lines[1]);
        assertEquals("src/main/", lines[2]);
        assertEquals("src/main/java/", lines[3]);
        assertEquals("src/main/java/demo/", lines[4]);
        assertEquals("src/main/java/demo/Calculator.java", lines[5]);
        assertEquals(6, lines.length);

        assertFalse(tree.contains(".git"), "denied entries must be invisible: " + tree);
        assertFalse(tree.contains(".env"), "denied entries must be invisible: " + tree);
    }

    @Test
    @DisplayName("listTree: maxDepth 0 lists only direct children")
    void listTreeDepthZero() {
        String tree = workspace.listTree("src", 0);
        assertEquals("src/main/\n", tree);
    }

    @Test
    @DisplayName("listTree: maxDepth above the policy limit is rejected, not silently clamped")
    void listTreeDepthLimit() {
        assertThrows(IllegalArgumentException.class, () -> workspace.listTree(null, 5));
        assertThrows(IllegalArgumentException.class, () -> workspace.listTree(null, -1));
        // policy maxDepth = 4 passes validation at the workspace level
        workspace.listTree(null, 4);
    }

    @Test
    @DisplayName("listTree: entry budget appends exactly one truncation marker")
    void listTreeEntryBudget() throws IOException {
        for (int i = 0; i < 10; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "x");
        }
        Workspace limited = Workspace.open(root, WorkspacePolicy.builder()
                .maxTreeEntries(3).build());

        String tree = limited.listTree(null, 0);
        String[] lines = tree.split("\n");

        assertEquals(3 + 1, lines.length, "3 entries + 1 marker: " + tree);
        assertEquals("[TRUNCATED: entry limit 3 reached]", lines[3]);
    }

    @Test
    @DisplayName("listTree: entry budget exactly consumed with nothing left over adds no marker")
    void listTreeBudgetExactlyConsumed() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        Files.writeString(root.resolve("b.txt"), "x");
        Workspace limited = Workspace.open(root, WorkspacePolicy.builder()
                .maxTreeEntries(4).build());

        // visible entries: a.txt, b.txt, pom.xml, src/ = exactly 4 at depth 0
        String tree = limited.listTree(null, 0);
        assertFalse(tree.contains("[TRUNCATED"), tree);
        assertEquals(4, tree.split("\n").length);
    }

    @Test
    @DisplayName("listTree: symlinks are not listed (no link following, no spoofing)")
    void listTreeSkipsSymlinks() throws IOException {
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "x");
        Files.createSymbolicLink(root.resolve("link.txt"), outside);

        String tree = workspace.listTree(null, 0);
        assertFalse(tree.contains("link.txt"), tree);
    }

    @Test
    @DisplayName("listTree: subtree listing, empty directory, and non-directory base")
    void listTreeSubtreeAndErrors() throws IOException {
        assertEquals("src/main/java/demo/Calculator.java\n",
                workspace.listTree("src/main/java/demo", 1));

        Files.createDirectories(root.resolve("empty-dir"));
        assertEquals("(empty directory)", workspace.listTree("empty-dir", 2));

        assertThrows(IllegalArgumentException.class, () -> workspace.listTree("pom.xml", 1));
        assertThrows(IllegalArgumentException.class, () -> workspace.listTree(".git", 1));
    }

    // ============ helpers ============

    private void assertDenied(String path) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> workspace.readFile(path));
        assertTrue(e.getMessage().contains("denied"), e.getMessage());
    }
}
