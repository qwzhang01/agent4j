package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.Chunker;
import io.github.qwzhang01.agent.rag.DocumentLoader;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Keeps a {@link ChunkIndex} in sync with a corpus directory.
 * <p>
 * The document id of a file is its root-relative path with {@code /} separators. A file is
 * re-indexed only when the SHA-256 of its bytes differs from {@link ChunkIndex#contentHash};
 * documents whose file is gone are deleted. A failing file is reported and skipped (its
 * previous version, if any, stays indexed); when embedding fails the document is indexed
 * keyword-only and reported as degraded.
 * <p>
 * File selection: hidden files and directories are skipped; a file must be supported by the
 * loader, match an include glob (when any are set) and match no exclude glob. Globs containing
 * {@code /} are matched against the root-relative path, others against the file name.
 */
public final class IncrementalIndexer {

    private static final Logger log = LoggerFactory.getLogger(IncrementalIndexer.class);

    public static final int DEFAULT_EMBED_BATCH_SIZE = 16;

    private final ChunkIndex index;
    private final DocumentLoader loader;
    private final Chunker chunker;
    private final EmbeddingClient embeddings;
    private final int embedBatchSize;
    private final List<Glob> includes;
    private final List<Glob> excludes;

    private IncrementalIndexer(Builder b) {
        this.index = Objects.requireNonNull(b.index, "index");
        this.loader = Objects.requireNonNull(b.loader, "loader");
        this.chunker = Objects.requireNonNull(b.chunker, "chunker");
        this.embeddings = b.embeddings;
        this.embedBatchSize = b.embedBatchSize;
        this.includes = b.includes.stream().map(Glob::new).toList();
        this.excludes = b.excludes.stream().map(Glob::new).toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Indexes new and changed files under {@code root} and removes documents whose file is gone. */
    public synchronized SyncReport sync(Path root) {
        Path base = normalizeRoot(root);
        long start = System.nanoTime();
        Accumulator acc = new Accumulator();
        List<Path> files = listFiles(base);
        TreeSet<String> present = new TreeSet<>();
        for (Path file : files) {
            String docId = docId(base, file);
            present.add(docId);
            process(file, docId, acc);
        }
        for (String docId : index.docIds()) {
            if (!present.contains(docId)) {
                deleteDoc(docId, acc);
            }
        }
        return acc.toReport(start);
    }

    /**
     * Brings a single file's document up to date: indexes it when new or changed, deletes it
     * when the file no longer exists or is no longer selected.
     *
     * @param file absolute, or relative to {@code root}; must lie under {@code root}
     */
    public synchronized SyncReport syncFile(Path root, Path file) {
        Path base = normalizeRoot(root);
        Objects.requireNonNull(file, "file");
        Path resolved = base.resolve(file).toAbsolutePath().normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException(file + " is not under " + base);
        }
        long start = System.nanoTime();
        Accumulator acc = new Accumulator();
        String docId = docId(base, resolved);
        if (Files.isRegularFile(resolved) && accepts(base, resolved)) {
            process(resolved, docId, acc);
        } else if (index.contentHash(docId).isPresent()) {
            deleteDoc(docId, acc);
        }
        return acc.toReport(start);
    }

    private void process(Path file, String docId, Accumulator acc) {
        try {
            String hash = sha256(Files.readAllBytes(file));
            Optional<String> previous = index.contentHash(docId);
            if (previous.isPresent() && previous.get().equals(hash)) {
                acc.unchanged.add(docId);
                return;
            }
            ParsedDocument parsed = loader.load(file, docId);
            List<Chunk> chunks = chunker.chunk(parsed);
            List<float[]> vectors = embed(docId, chunks, acc);
            index.upsert(docId, hash, chunks, vectors);
            acc.chunksWritten += chunks.size();
            (previous.isPresent() ? acc.updated : acc.added).add(docId);
            log.debug("Indexed {} ({} chunks)", docId, chunks.size());
        } catch (IOException | RuntimeException e) {
            fail(docId, e, acc);
        }
    }

    private List<float[]> embed(String docId, List<Chunk> chunks, Accumulator acc) {
        float[][] vectors = new float[chunks.size()][];
        if (embeddings == null || chunks.isEmpty()) {
            return Arrays.asList(vectors);
        }
        List<Integer> positions = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i).contextualText();
            if (!text.isBlank()) {
                positions.add(i);
                texts.add(text);
            }
        }
        try {
            for (int from = 0; from < texts.size(); from += embedBatchSize) {
                int to = Math.min(from + embedBatchSize, texts.size());
                List<float[]> batch = embeddings.embedAll(texts.subList(from, to));
                if (batch == null || batch.size() != to - from) {
                    throw new IllegalStateException("embedding provider returned "
                            + (batch == null ? "null" : batch.size() + " vectors") + " for " + (to - from) + " texts");
                }
                for (int j = 0; j < batch.size(); j++) {
                    vectors[positions.get(from + j)] = batch.get(j);
                }
            }
            return Arrays.asList(vectors);
        } catch (RuntimeException e) {
            log.warn("Embedding failed for {}; indexing keyword-only: {}", docId, e.getMessage());
            acc.degraded.put(docId, "embedding failed: " + message(e));
            return Arrays.asList(new float[chunks.size()][]);
        }
    }

    private void deleteDoc(String docId, Accumulator acc) {
        try {
            index.delete(docId);
            acc.deleted.add(docId);
        } catch (RuntimeException e) {
            fail(docId, e, acc);
        }
    }

    private static void fail(String docId, Exception e, Accumulator acc) {
        log.warn("Failed to index {}: {}", docId, e.toString());
        acc.failed.put(docId, message(e));
    }

    private List<Path> listFiles(Path base) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(base) && isHidden(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && accepts(base, file)) {
                        files.add(file);
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

    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    private static Path normalizeRoot(Path root) {
        Objects.requireNonNull(root, "root");
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }
        return base;
    }

    private static String docId(Path base, Path file) {
        List<String> parts = new ArrayList<>();
        base.relativize(file).forEach(p -> parts.add(p.toString()));
        return String.join("/", parts);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Outcome of one sync call.
     *
     * @param added         documents indexed for the first time
     * @param updated       documents re-indexed because their content changed
     * @param unchanged     documents skipped because their hash matched
     * @param deleted       documents removed because their file is gone or no longer selected
     * @param failed        documents that could not be read, parsed, chunked or written, with the reason;
     *                      their previous version (if any) is left in place
     * @param degraded      documents indexed keyword-only because embedding failed, with the reason
     * @param chunksWritten chunks written by this call
     * @param durationMs    wall-clock duration
     */
    public record SyncReport(
            List<String> added,
            List<String> updated,
            List<String> unchanged,
            List<String> deleted,
            Map<String, String> failed,
            Map<String, String> degraded,
            int chunksWritten,
            long durationMs
    ) {
        public SyncReport {
            added = List.copyOf(added);
            updated = List.copyOf(updated);
            unchanged = List.copyOf(unchanged);
            deleted = List.copyOf(deleted);
            failed = Map.copyOf(failed);
            degraded = Map.copyOf(degraded);
        }

        /** True when anything was added, updated or deleted. */
        public boolean changed() {
            return !added.isEmpty() || !updated.isEmpty() || !deleted.isEmpty();
        }
    }

    private static final class Accumulator {
        final List<String> added = new ArrayList<>();
        final List<String> updated = new ArrayList<>();
        final List<String> unchanged = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final Map<String, String> failed = new LinkedHashMap<>();
        final Map<String, String> degraded = new LinkedHashMap<>();
        int chunksWritten;

        SyncReport toReport(long startNanos) {
            return new SyncReport(added, updated, unchanged, deleted, failed, degraded, chunksWritten,
                    (System.nanoTime() - startNanos) / 1_000_000);
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

    /** Builder; {@code index}, {@code loader} and {@code chunker} are required. */
    public static final class Builder {
        private ChunkIndex index;
        private DocumentLoader loader;
        private Chunker chunker;
        private EmbeddingClient embeddings;
        private int embedBatchSize = DEFAULT_EMBED_BATCH_SIZE;
        private final List<String> includes = new ArrayList<>();
        private final List<String> excludes = new ArrayList<>();

        private Builder() {
        }

        public Builder index(ChunkIndex index) {
            this.index = index;
            return this;
        }

        public Builder loader(DocumentLoader loader) {
            this.loader = loader;
            return this;
        }

        public Builder chunker(Chunker chunker) {
            this.chunker = chunker;
            return this;
        }

        /** Embedding provider; null (the default) indexes keyword-only. */
        public Builder embeddings(EmbeddingClient embeddings) {
            this.embeddings = embeddings;
            return this;
        }

        /** Texts per {@link EmbeddingClient#embedAll} call; default {@value #DEFAULT_EMBED_BATCH_SIZE}. */
        public Builder embedBatchSize(int embedBatchSize) {
            if (embedBatchSize < 1) {
                throw new IllegalArgumentException("embedBatchSize must be >= 1: " + embedBatchSize);
            }
            this.embedBatchSize = embedBatchSize;
            return this;
        }

        /** Adds an include glob, e.g. {@code *.md} or {@code docs/**}. */
        public Builder include(String glob) {
            includes.add(Objects.requireNonNull(glob, "glob"));
            return this;
        }

        /** Adds an exclude glob, e.g. {@code drafts/**}. */
        public Builder exclude(String glob) {
            excludes.add(Objects.requireNonNull(glob, "glob"));
            return this;
        }

        public IncrementalIndexer build() {
            return new IncrementalIndexer(this);
        }
    }
}
