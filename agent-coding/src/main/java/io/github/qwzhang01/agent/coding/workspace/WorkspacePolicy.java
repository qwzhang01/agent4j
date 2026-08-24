package io.github.qwzhang01.agent.coding.workspace;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Path boundary SSOT for a {@link Workspace} (Stage 17 M17.1, blueprint: "reading is a
 * privilege too").
 * <p>
 * Owns three limits that keep a Coding Agent's context budget and safety surface sane:
 * <ul>
 *   <li>{@code denyGlobs} - paths that may not even be read (.git internals, .env secrets,
 *       private keys). A path is denied when it matches a glob <b>or when any of its
 *       ancestors matches one</b> (ancestor propagation: denying {@code .git} denies the
 *       whole subtree).</li>
 *   <li>{@code maxFileBytes} - single-file read cap; larger files are returned truncated
 *       with an explicit marker instead of silently flooding the context.</li>
 *   <li>{@code maxTreeEntries} / {@code maxDepth} - directory listing caps.</li>
 * </ul>
 * <p>
 * Fail-fast construction: glob patterns are compiled to {@link PathMatcher}s in
 * {@link Builder#build()}; a malformed pattern throws immediately rather than failing
 * (or silently passing) at match time.
 * <p>
 * Immutable; {@link #denyGlobs()} returns a defensive copy.
 */
public final class WorkspacePolicy {

    /**
     * Default deny set: version-control internals, environment/secrets files, private keys.
     * {@code .git} and {@code .git/**} both listed - the first denies the directory itself
     * (ancestor propagation covers its contents), the second is an explicit belt-and-suspenders.
     */
    public static final List<String> DEFAULT_DENY_GLOBS = List.of(
            ".git", ".git/**",
            ".env*", "**/.env*",
            "*.key", "**/*.key");

    public static final long DEFAULT_MAX_FILE_BYTES = 64 * 1024;
    public static final int DEFAULT_MAX_TREE_ENTRIES = 500;
    public static final int DEFAULT_MAX_DEPTH = 4;

    private final List<String> denyGlobs;
    private final List<PathMatcher> denyMatchers;
    private final long maxFileBytes;
    private final int maxTreeEntries;
    private final int maxDepth;

    private WorkspacePolicy(Builder builder) {
        this.denyGlobs = List.copyOf(builder.denyGlobs);
        this.denyMatchers = compileMatchers(this.denyGlobs);
        this.maxFileBytes = builder.maxFileBytes;
        this.maxTreeEntries = builder.maxTreeEntries;
        this.maxDepth = builder.maxDepth;
    }

    private static List<PathMatcher> compileMatchers(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>(globs.size());
        for (String glob : globs) {
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            } catch (IllegalArgumentException e) {
                // fail-fast at construction: a pattern that cannot compile must never
                // silently pass (or silently fail) at match time
                throw new IllegalArgumentException("invalid deny glob pattern '" + glob + "': "
                        + e.getMessage(), e);
            }
        }
        return List.copyOf(matchers);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Is this workspace-relative path denied?
     * <p>
     * A path is denied when it matches any deny glob, <b>or when any ancestor of it does</b>
     * (so denying a directory name denies everything beneath it). The empty/root path is
     * never denied by itself.
     *
     * @param relativePath workspace-relative path (normalized internally)
     */
    public boolean isDenied(Path relativePath) {
        Path p = Objects.requireNonNull(relativePath, "relativePath must not be null").normalize();
        while (p != null && p.getNameCount() > 0) {
            for (PathMatcher matcher : denyMatchers) {
                if (matcher.matches(p)) {
                    return true;
                }
            }
            p = p.getParent();
        }
        return false;
    }

    public List<String> denyGlobs() {
        return denyGlobs;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    public int maxTreeEntries() {
        return maxTreeEntries;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public static final class Builder {
        private List<String> denyGlobs = DEFAULT_DENY_GLOBS;
        private long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        private int maxTreeEntries = DEFAULT_MAX_TREE_ENTRIES;
        private int maxDepth = DEFAULT_MAX_DEPTH;

        public Builder denyGlobs(List<String> globs) {
            this.denyGlobs = List.copyOf(Objects.requireNonNull(globs, "globs must not be null"));
            return this;
        }

        public Builder maxFileBytes(long maxFileBytes) {
            if (maxFileBytes <= 0) {
                throw new IllegalArgumentException("maxFileBytes must be positive");
            }
            this.maxFileBytes = maxFileBytes;
            return this;
        }

        public Builder maxTreeEntries(int maxTreeEntries) {
            if (maxTreeEntries <= 0) {
                throw new IllegalArgumentException("maxTreeEntries must be positive");
            }
            this.maxTreeEntries = maxTreeEntries;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            if (maxDepth <= 0) {
                throw new IllegalArgumentException("maxDepth must be positive");
            }
            this.maxDepth = maxDepth;
            return this;
        }

        public WorkspacePolicy build() {
            return new WorkspacePolicy(this);
        }
    }
}
