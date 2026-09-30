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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps a {@link ChunkIndex} in sync with a corpus directory.
 * <p>
 * The document id of a file is its root-relative path with {@code /} separators. A file is
 * re-indexed only when the SHA-256 of its bytes differs from {@link ChunkIndex#contentHash};
 * documents whose file is gone are deleted. A failing file is reported and skipped (its
 * previous version, if any, stays indexed); when embedding fails the document is indexed
 * keyword-only and reported as degraded.
 * <p>
 * File selection: hidden files and directories are skipped, and symbolic links below the root are
 * not followed (the root itself may be a link); a file must be supported by the loader, match an
 * include glob (when any are set) and match no exclude glob. Globs containing {@code /} are matched
 * against the root-relative path, others against the file name. A file that disappears while being
 * indexed is treated as deleted.
 */
public final class IncrementalIndexer {

    private static final Logger log = LoggerFactory.getLogger(IncrementalIndexer.class);

    public static final int DEFAULT_EMBED_BATCH_SIZE = 16;

    private final ChunkIndex index;
    private final DocumentLoader loader;
    private final Chunker chunker;
    private final EmbeddingClient embeddings;
    private final int embedBatchSize;
    private final CorpusScanner scanner;

    private IncrementalIndexer(Builder b) {
        this.index = Objects.requireNonNull(b.index, "index");
        this.loader = Objects.requireNonNull(b.loader, "loader");
        this.chunker = Objects.requireNonNull(b.chunker, "chunker");
        this.embeddings = b.embeddings;
        this.embedBatchSize = b.embedBatchSize;
        this.scanner = new CorpusScanner(loader, b.includes, b.excludes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Indexes new and changed files under {@code root} and removes documents whose file is gone.
     * The whole pass runs in one {@link ChunkIndex#bulk}; with a {@link LuceneChunkIndex} that
     * commits once at the end instead of once per document.
     */
    public synchronized SyncReport sync(Path root) {
        Path base = CorpusScanner.normalizeRoot(root);
        long start = System.nanoTime();
        Accumulator acc = new Accumulator();
        List<Path> files = scanner.list(base);
        index.bulk(() -> {
            Set<String> present = new HashSet<>();
            for (Path file : files) {
                String docId = CorpusScanner.docId(base, file);
                present.add(docId);
                process(file, docId, acc);
            }
            for (String docId : index.docIds()) {
                if (!present.contains(docId) && !acc.deleted.contains(docId)) {
                    deleteDoc(docId, acc);
                }
            }
        });
        return acc.toReport(start);
    }

    /**
     * Brings a single file's document up to date: indexes it when new or changed, deletes it
     * when the file no longer exists or is no longer selected.
     *
     * @param file absolute, or relative to {@code root}; must lie under {@code root}
     */
    public synchronized SyncReport syncFile(Path root, Path file) {
        Path base = CorpusScanner.normalizeRoot(root);
        Objects.requireNonNull(file, "file");
        Path resolved = base.resolve(file).toAbsolutePath().normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException(file + " is not under " + base);
        }
        long start = System.nanoTime();
        Accumulator acc = new Accumulator();
        String docId = CorpusScanner.docId(base, resolved);
        if (scanner.selects(base, resolved)) {
            process(resolved, docId, acc);
        } else if (index.contentHash(docId).isPresent()) {
            deleteDoc(docId, acc);
        }
        return acc.toReport(start);
    }

    private void process(Path file, String docId, Accumulator acc) {
        Optional<String> previous = Optional.empty();
        try {
            String hash = sha256(Files.readAllBytes(file));
            previous = index.contentHash(docId);
            if (previous.isPresent() && previous.get().equals(hash)) {
                acc.unchanged.add(docId);
                return;
            }
            ParsedDocument parsed = loader.load(file, docId);
            List<Chunk> chunks = chunker.chunk(parsed);
            List<float[]> vectors = embed(docId, chunks, acc);
            // The file may have changed after hashing; record the hash of what the loader parsed.
            index.upsert(docId, parsed.contentHash(), chunks, vectors);
            acc.chunksWritten += chunks.size();
            (previous.isPresent() ? acc.updated : acc.added).add(docId);
            log.debug("Indexed {} ({} chunks)", docId, chunks.size());
        } catch (NoSuchFileException e) {
            log.debug("{} vanished during sync", docId);
            if (previous.isPresent() || index.contentHash(docId).isPresent()) {
                deleteDoc(docId, acc);
            }
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
