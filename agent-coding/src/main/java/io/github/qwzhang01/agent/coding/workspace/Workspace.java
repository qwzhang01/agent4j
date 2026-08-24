package io.github.qwzhang01.agent.coding.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A view of - and a boundary around - one directory tree on the real file system
 * (Stage 17 M17.1, blueprint D1: "the workspace is a view + boundary, not a copy").
 * <p>
 * Unlike {@code WorldState} (Stage 16, engine-owned blackboard) or {@code MemoryStore}
 * (Stage 8, store-owned sediment), the workspace is <b>external pre-existing fact</b>:
 * the files are already there, the Agent only reads them. Whoever owns the state owns
 * its change discipline - for the file system that discipline is the patch flow (M17.2).
 * <p>
 * This class enforces two layers of path safety:
 * <ol>
 *   <li><b>Lexical</b> - {@link #resolve(String)} normalizes and requires the result to
 *       stay under the root: {@code ../} chains, absolute paths and blank input all fail.</li>
 *   <li><b>Real</b> - {@link #readFile(String)} additionally resolves the real path, so a
 *       symbolic link pointing outside the root is rejected (symlink escape).</li>
 * </ol>
 * Reads are also a privilege: paths denied by {@link WorkspacePolicy} cannot be read or
 * listed. Files above the byte cap are returned truncated with an explicit marker.
 * <p>
 * Immutable; errors are {@link IllegalArgumentException} (fail-fast, programmer-facing).
 * Tool wrappers translate them into model-readable observations.
 */
public final class Workspace {

    private final Path root;
    private final Path realRoot;
    private final WorkspacePolicy policy;

    private Workspace(Path root, WorkspacePolicy policy) throws IOException {
        this.root = root;
        this.realRoot = root.toRealPath();
        this.policy = policy;
    }

    /**
     * Open the workspace rooted at {@code root}.
     *
     * @throws IllegalArgumentException if root does not exist or is not a directory
     */
    public static Workspace open(Path root) {
        return open(root, WorkspacePolicy.builder().build());
    }

    public static Workspace open(Path root, WorkspacePolicy policy) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Path abs = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            throw new IllegalArgumentException(
                    "workspace root must be an existing directory: " + abs);
        }
        try {
            return new Workspace(abs, policy);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "cannot resolve workspace root " + abs + ": " + e.getMessage(), e);
        }
    }

    public Path root() {
        return root;
    }

    public WorkspacePolicy policy() {
        return policy;
    }

    // ============ Path Resolution (lexical safety) ============

    /**
     * Resolve a workspace-relative path to an absolute path under the root.
     * <p>
     * Fail-fast on the three escape forms: blank input, absolute paths, and {@code ../}
     * chains that normalize out of the root.
     *
     * @throws IllegalArgumentException on blank/absolute/escaping input
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path must not be null or blank");
        }
        Path rel = Path.of(relative);
        if (rel.isAbsolute()) {
            throw new IllegalArgumentException("absolute paths are not allowed, use a path "
                    + "relative to the workspace root: " + relative);
        }
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "path escapes the workspace root: " + relative);
        }
        return resolved;
    }

    /** Workspace-relative form of {@link #resolve(String)} (never empty here: input is non-blank). */
    Path relative(String relative) {
        return root.relativize(resolve(relative));
    }

    // ============ Read (real-path safety + policy + budget) ============

    /**
     * Read a file as UTF-8 text.
     * <ul>
     *   <li>policy-denied path -> {@link IllegalArgumentException}</li>
     *   <li>symbolic link escaping the root -> {@link IllegalArgumentException}</li>
     *   <li>missing path / not a regular file -> {@link IllegalArgumentException}</li>
     *   <li>file larger than {@code maxFileBytes} -> first {@code maxFileBytes} bytes plus a
     *       {@code [TRUNCATED: ...]} marker (honest boundary: the cut may split a multi-byte
     *       UTF-8 character at the very end)</li>
     *   <li>empty file -> {@code "(empty file)"}</li>
     * </ul>
     */
    public String readFile(String relative) {
        Path abs = resolve(relative);
        Path rel = root.relativize(abs);

        // privilege check: reading is also gated (lexical level, v1 honest boundary)
        if (policy.isDenied(rel)) {
            throw new IllegalArgumentException("path is denied by workspace policy: " + rel
                    + " (reading is a privilege too)");
        }

        // real-path check: a symlink must not smuggle content from outside the root
        Path real;
        try {
            real = abs.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("no such file: " + relative);
        }
        if (!real.startsWith(realRoot)) {
            throw new IllegalArgumentException("symbolic link escapes the workspace: " + relative);
        }
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException("not a regular file: " + relative);
        }

        long size;
        try {
            size = Files.size(real);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot stat file: " + relative + " (" + e.getMessage() + ")");
        }
        if (size == 0) {
            return "(empty file)";
        }
        if (size <= policy.maxFileBytes()) {
            try {
                return Files.readString(real, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read file: " + relative + " (" + e.getMessage() + ")");
            }
        }
        // over budget: stream only the first maxFileBytes bytes, never load the whole file
        try (InputStream in = Files.newInputStream(real)) {
            byte[] head = in.readNBytes((int) policy.maxFileBytes());
            String content = new String(head, StandardCharsets.UTF_8);
            return content + "\n[TRUNCATED: showing " + head.length + " of " + size + " bytes]";
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read file: " + relative + " (" + e.getMessage() + ")");
        }
    }

    // ============ Listing (deterministic, policy-filtered, budgeted) ============

    /**
     * List the tree under {@code relative} (blank/null means the root itself).
     * <p>
     * One path per line, sorted by name, directories suffixed with {@code '/'}; paths are
     * workspace-relative so the model can feed them straight back into {@code read_file}.
     * Policy-denied entries and symbolic links are not listed (a symlink would advertise a
     * path that is not what it claims to be). Depth is capped by {@code maxDepth}; entry
     * count by {@code maxTreeEntries} - hitting either appends an explicit truncation marker.
     *
     * @param maxDepth 0 lists only the direct children of {@code relative}
     * @throws IllegalArgumentException if the base is not a directory, or maxDepth is
     *                                  negative or above the policy limit
     */
    public String listTree(String relative, int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative: " + maxDepth);
        }
        if (maxDepth > policy.maxDepth()) {
            throw new IllegalArgumentException("maxDepth " + maxDepth + " exceeds the policy limit "
                    + policy.maxDepth());
        }
        Path base = (relative == null || relative.isBlank()) ? root : resolve(relative);
        Path baseRel = root.relativize(base);
        if (policy.isDenied(baseRel)) {
            throw new IllegalArgumentException("path is denied by workspace policy: " + baseRel);
        }
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("not a directory: " + relative);
        }

        StringBuilder out = new StringBuilder();
        // shared cursor across the recursion: one counting point, one truncation point
        int[] listed = {0};
        boolean[] truncated = {false};
        listDirectory(base, baseRel, 0, maxDepth, out, listed, truncated);
        if (truncated[0]) {
            out.append("[TRUNCATED: entry limit ").append(policy.maxTreeEntries())
                    .append(" reached]\n");
        }
        if (listed[0] == 0) {
            return "(empty directory)";
        }
        return out.toString();
    }

    /**
     * Depth-first listing, sorted per directory for deterministic output. Mutates the shared
     * {@code listed}/{@code truncated} cursor so the whole tree shares one entry budget and
     * at most one truncation marker.
     */
    private void listDirectory(Path dir, Path dirRel, int depth, int maxDepth,
                               StringBuilder out, int[] listed, boolean[] truncated) {
        if (truncated[0]) {
            return;
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "cannot list directory " + dirRel + ": " + e.getMessage(), e);
        }

        for (Path child : children) {
            if (truncated[0]) {
                return;
            }
            Path childRel = dirRel.resolve(child.getFileName());

            // policy-denied entries are invisible: neither listed nor recursed into
            if (policy.isDenied(childRel)) {
                continue;
            }
            // symlinks are not listed at all (v1 honest boundary: no link following, no spoofing)
            if (Files.isSymbolicLink(child)) {
                continue;
            }

            if (listed[0] >= policy.maxTreeEntries()) {
                truncated[0] = true;
                return;
            }

            boolean isDir = Files.isDirectory(child);
            out.append(childRel).append(isDir ? "/" : "").append('\n');
            listed[0]++;

            if (isDir && depth < maxDepth) {
                listDirectory(child, childRel, depth + 1, maxDepth, out, listed, truncated);
            }
        }
    }
}
