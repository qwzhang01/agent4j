package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.rag.DocumentLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** File selection and document-id mapping for {@link IncrementalIndexer}; see its class doc for the rules. */
final class CorpusScanner {

    private static final Logger log = LoggerFactory.getLogger(CorpusScanner.class);

    private final DocumentLoader loader;
    private final List<Glob> includes;
    private final List<Glob> excludes;

    CorpusScanner(DocumentLoader loader, List<String> includes, List<String> excludes) {
        this.loader = loader;
        this.includes = includes.stream().map(Glob::new).toList();
        this.excludes = excludes.stream().map(Glob::new).toList();
    }

    /** Absolute, normalized form of {@code root}; must be a directory. */
    static Path normalizeRoot(Path root) {
        Objects.requireNonNull(root, "root");
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }
        return base;
    }

    /** Root-relative path with {@code /} separators. */
    static String docId(Path base, Path file) {
        List<String> parts = new ArrayList<>();
        base.relativize(file).forEach(p -> parts.add(p.toString()));
        return String.join("/", parts);
    }

    /** Selected files under {@code base}, sorted. The walk does not follow links below the root. */
    List<Path> list(Path base) {
        Path start = realPath(base);
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(start) && isHidden(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && accepts(start, file)) {
                        files.add(base.resolve(start.relativize(file)));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    log.warn("Cannot visit {}: {}", file, e.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk " + base, e);
        }
        files.sort(null);
        return files;
    }

    /** Whether {@link #list} would return {@code file}. */
    boolean selects(Path base, Path file) {
        return isWalkable(base, file) && accepts(base, file);
    }

    private boolean accepts(Path base, Path file) {
        Path relative = base.relativize(file);
        for (Path part : relative) {
            if (isHidden(part)) {
                return false;
            }
        }
        if (!loader.supports(file)) {
            return false;
        }
        if (!includes.isEmpty() && includes.stream().noneMatch(g -> g.matches(relative))) {
            return false;
        }
        return excludes.stream().noneMatch(g -> g.matches(relative));
    }

    private static boolean isWalkable(Path base, Path file) {
        Path current = base;
        for (Path part : base.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return false;
            }
        }
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    private static Path realPath(Path base) {
        try {
            return base.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve " + base, e);
        }
    }

    private static final class Glob {
        private final PathMatcher matcher;
        private final boolean matchPath;

        Glob(String pattern) {
            this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            this.matchPath = pattern.contains("/");
        }

        boolean matches(Path relative) {
            return matchPath ? matcher.matches(relative) : matcher.matches(relative.getFileName());
        }
    }
}
