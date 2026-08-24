package io.github.qwzhang01.agent.coding.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.1: the path-boundary SSOT.
 * <p>
 * Under test: default deny set, ancestor propagation (deny a directory -> deny its
 * subtree), fail-fast glob compilation, and builder validation.
 */
class WorkspacePolicyTest {

    @Test
    @DisplayName("defaults: deny set covers vcs internals, env secrets, keys; budgets are sane")
    void defaults() {
        WorkspacePolicy policy = WorkspacePolicy.builder().build();

        assertEquals(WorkspacePolicy.DEFAULT_DENY_GLOBS, policy.denyGlobs());
        assertTrue(policy.denyGlobs().contains(".git"));
        assertTrue(policy.denyGlobs().contains(".git/**"));
        assertEquals(64 * 1024, policy.maxFileBytes());
        assertEquals(500, policy.maxTreeEntries());
        assertEquals(4, policy.maxDepth());
    }

    @Test
    @DisplayName("default deny set: every entry actually denies a representative path")
    void defaultDenyGlobsMatch() {
        WorkspacePolicy policy = WorkspacePolicy.builder().build();

        assertTrue(policy.isDenied(Path.of(".git")), ".git directory itself");
        assertTrue(policy.isDenied(Path.of(".git/config")), ".git/config via .git ancestor");
        assertTrue(policy.isDenied(Path.of(".git/hooks/pre-commit")), "deep .git content");
        assertTrue(policy.isDenied(Path.of(".env")), ".env");
        assertTrue(policy.isDenied(Path.of(".env.production")), ".env.production");
        assertTrue(policy.isDenied(Path.of("config/.env.local")), "nested .env* file");
        assertTrue(policy.isDenied(Path.of("server.key")), "root-level key");
        assertTrue(policy.isDenied(Path.of("config/server.key")), "nested key");

        // normal project paths stay readable
        assertFalse(policy.isDenied(Path.of("src/main/java/App.java")));
        assertFalse(policy.isDenied(Path.of("pom.xml")));
        assertFalse(policy.isDenied(Path.of("docs/.gitignore-notes.md"))); // name merely contains ".git"
    }

    @Test
    @DisplayName("ancestor propagation: denying a directory name denies its whole subtree")
    void ancestorPropagation() {
        WorkspacePolicy policy = WorkspacePolicy.builder()
                .denyGlobs(List.of("secrets"))
                .build();

        assertTrue(policy.isDenied(Path.of("secrets")));
        assertTrue(policy.isDenied(Path.of("secrets/api-token.txt")));
        assertTrue(policy.isDenied(Path.of("secrets/nested/deeper/creds")));
        assertFalse(policy.isDenied(Path.of("src/secrets.txt"))); // sibling name, not the directory
    }

    @Test
    @DisplayName("custom globs replace the default set entirely")
    void customGlobsReplaceDefaults() {
        WorkspacePolicy policy = WorkspacePolicy.builder()
                .denyGlobs(List.of("target/**"))
                .build();

        assertTrue(policy.isDenied(Path.of("target/classes/App.class")));
        assertFalse(policy.isDenied(Path.of(".git/config")), "default set was replaced, .git is allowed now");
    }

    @Test
    @DisplayName("malformed glob fails fast at build time, never at match time")
    void malformedGlobFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePolicy.builder().denyGlobs(List.of("[")).build());
    }

    @Test
    @DisplayName("builder validation: non-positive budgets are rejected")
    void builderValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePolicy.builder().maxFileBytes(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePolicy.builder().maxTreeEntries(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePolicy.builder().maxDepth(-1).build());
        assertThrows(NullPointerException.class,
                () -> WorkspacePolicy.builder().denyGlobs(null).build());
    }

    @Test
    @DisplayName("isDenied null input is rejected fast")
    void nullPathRejected() {
        WorkspacePolicy policy = WorkspacePolicy.builder().build();
        assertThrows(NullPointerException.class, () -> policy.isDenied(null));
    }
}
