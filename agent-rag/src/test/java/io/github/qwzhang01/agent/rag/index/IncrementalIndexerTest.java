package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.rag.Chunker;
import io.github.qwzhang01.agent.rag.DocumentLoader;
import io.github.qwzhang01.agent.rag.model.BlockType;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.DocumentBlock;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;
import io.github.qwzhang01.agent.rag.index.IncrementalIndexer.SyncReport;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalIndexerTest {

    private static final int DIMS = 32;

    @TempDir
    Path root;

    private LuceneChunkIndex index;
    private FakeEmbeddingClient embedder;

    @BeforeEach
    void setUp() {
        index = LuceneChunkIndex.inMemory(DIMS);
        embedder = new FakeEmbeddingClient(DIMS);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    private IncrementalIndexer.Builder builder() {
        return IncrementalIndexer.builder()
                .index(index)
                .loader(new ParagraphLoader())
                .chunker(new BlockChunker())
                .embeddings(embedder);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void firstSyncAddsEveryDocumentWithVectors() throws IOException {
        write("a.txt", "alpha one\n\nalpha two");
        write("sub/dir/b.txt", "bravo");

        SyncReport report = builder().build().sync(root);

        assertEquals(List.of("a.txt", "sub/dir/b.txt"), report.added());
        assertEquals(3, report.chunksWritten());
        assertTrue(report.failed().isEmpty());
        assertTrue(report.degraded().isEmpty());
        assertTrue(report.changed());
        assertTrue(report.durationMs() >= 0);
        assertEquals(Set.of("a.txt", "sub/dir/b.txt"), index.docIds());
        assertEquals(Optional.of(IncrementalIndexer.sha256("bravo".getBytes(StandardCharsets.UTF_8))),
                index.contentHash("sub/dir/b.txt"));
        List<String> vectorHits = index.vectorSearch(embedder.embed("bravo"), 1, Map.of()).stream()
                .map(h -> h.chunk().chunkId()).toList();
        assertEquals(List.of("sub/dir/b.txt#0"), vectorHits);
    }

    @Test
    void unchangedFilesAreSkipped() throws IOException {
        write("a.txt", "alpha");
        IncrementalIndexer indexer = builder().build();
        indexer.sync(root);
        int callsAfterFirst = embedder.batchCalls();

        SyncReport report = indexer.sync(root);

        assertEquals(List.of("a.txt"), report.unchanged());
        assertTrue(report.added().isEmpty());
        assertEquals(0, report.chunksWritten());
        assertFalse(report.changed());
        assertEquals(callsAfterFirst, embedder.batchCalls());
    }

    @Test
    void modifiedFileReplacesOldChunks() throws IOException {
        write("a.txt", "legacy text\n\nmore legacy");
        IncrementalIndexer indexer = builder().build();
        indexer.sync(root);

        write("a.txt", "fresh text");
        SyncReport report = indexer.sync(root);

        assertEquals(List.of("a.txt"), report.updated());
        assertEquals(1, report.chunksWritten());
        assertEquals(1, index.size());
        assertTrue(index.keywordSearch("legacy", 5, Map.of()).isEmpty());
        assertEquals(1, index.keywordSearch("fresh", 5, Map.of()).size());
    }

    @Test
    void removedFileIsDeleted() throws IOException {
        Path a = write("a.txt", "alpha");
        write("b.txt", "bravo");
        IncrementalIndexer indexer = builder().build();
        indexer.sync(root);

        Files.delete(a);
        SyncReport report = indexer.sync(root);

        assertEquals(List.of("a.txt"), report.deleted());
        assertEquals(List.of("b.txt"), report.unchanged());
        assertEquals(Set.of("b.txt"), index.docIds());
    }

    @Test
    void embeddingFailureIndexesKeywordOnlyAndReportsDegradation() throws IOException {
        write("a.txt", "alpha");
        embedder.failing(true);

        SyncReport report = builder().build().sync(root);

        assertEquals(List.of("a.txt"), report.added());
        assertTrue(report.degraded().get("a.txt").contains("embedding service down"));
        assertTrue(report.failed().isEmpty());
        assertEquals(1, index.keywordSearch("alpha", 5, Map.of()).size());
        embedder.failing(false);
        assertTrue(index.vectorSearch(embedder.embed("alpha"), 5, Map.of()).isEmpty());
    }

    @Test
    void embedderReturningWrongCountDegrades() throws IOException {
        write("a.txt", "alpha");
        EmbeddingClient broken = new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                return new float[DIMS];
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                return List.of();
            }
        };

        SyncReport report = builder().embeddings(broken).build().sync(root);

        assertEquals(List.of("a.txt"), report.added());
        assertTrue(report.degraded().containsKey("a.txt"));
    }

    @Test
    void parseFailureIsRecordedAndOtherFilesContinue() throws IOException {
        write("good.txt", "fine");
        write("bad.txt", "BROKEN content");

        SyncReport report = builder().build().sync(root);

        assertEquals(List.of("good.txt"), report.added());
        assertTrue(report.failed().get("bad.txt").contains("cannot parse"));
        assertEquals(Set.of("good.txt"), index.docIds());
    }

    @Test
    void failedUpdateKeepsPreviousVersion() throws IOException {
        write("a.txt", "stable version");
        IncrementalIndexer indexer = builder().build();
        indexer.sync(root);

        write("a.txt", "BROKEN edit");
        SyncReport report = indexer.sync(root);

        assertTrue(report.failed().containsKey("a.txt"));
        assertTrue(report.deleted().isEmpty());
        assertEquals(1, index.keywordSearch("stable", 5, Map.of()).size());
    }

    @Test
    void indexRejectionIsRecordedAsFailure() throws IOException {
        write("a.txt", "alpha");

        SyncReport report = builder().embeddings(new FakeEmbeddingClient(DIMS + 1)).build().sync(root);

        assertTrue(report.failed().containsKey("a.txt"));
        assertTrue(index.docIds().isEmpty());
    }

    @Test
    void chunkerFailureIsRecorded() throws IOException {
        write("a.txt", "alpha");
        Chunker exploding = doc -> {
            throw new IllegalStateException("chunker bug");
        };

        SyncReport report = builder().chunker(exploding).build().sync(root);

        assertEquals(Map.of("a.txt", "chunker bug"), report.failed());
    }

    @Test
    void withoutEmbeddingClientIndexesKeywordOnlyWithoutDegradation() throws IOException {
        write("a.txt", "alpha");

        SyncReport report = builder().embeddings(null).build().sync(root);

        assertEquals(List.of("a.txt"), report.added());
        assertTrue(report.degraded().isEmpty());
        assertTrue(index.vectorSearch(embedder.embed("alpha"), 5, Map.of()).isEmpty());
    }

    @Test
    void embeddingsAreBatched() throws IOException {
        write("a.txt", "p1\n\np2\n\np3\n\np4\n\np5");

        builder().embedBatchSize(2).build().sync(root);

        assertEquals(3, embedder.batchCalls());
        assertEquals(5, index.size());
    }

    @Test
    void emptyDocumentIsTrackedWithoutEmbeddingCalls() throws IOException {
        write("empty.txt", "");

        SyncReport report = builder().build().sync(root);

        assertEquals(List.of("empty.txt"), report.added());
        assertEquals(0, embedder.batchCalls());
        assertEquals(Set.of("empty.txt"), index.docIds());
    }

    @Test
    void hiddenAndUnsupportedFilesAreSkipped() throws IOException {
        write("a.txt", "alpha");
        write(".hidden.txt", "secret");
        write(".git/config.txt", "internal");
        write("notes.pdf", "binary");

        SyncReport report = builder().build().sync(root);

        assertEquals(List.of("a.txt"), report.added());
    }

    @Test
    void includeAndExcludeGlobsSelectFiles() throws IOException {
        write("docs/a.txt", "alpha");
        write("docs/drafts/b.txt", "bravo");
        write("other/c.txt", "charlie");
        write("skip-me.txt", "delta");

        SyncReport report = builder()
                .include("docs/**")
                .include("skip-*.txt")
                .exclude("docs/drafts/**")
                .exclude("skip-me.txt")
                .build().sync(root);

        assertEquals(List.of("docs/a.txt"), report.added());
    }

    @Test
    void newlyExcludedDocumentIsDeleted() throws IOException {
        write("a.txt", "alpha");
        write("b.txt", "bravo");
        builder().build().sync(root);

        SyncReport report = builder().exclude("b.txt").build().sync(root);

        assertEquals(List.of("b.txt"), report.deleted());
        assertEquals(Set.of("a.txt"), index.docIds());
    }

    @Test
    void syncFileHandlesAddUnchangedAndDelete() throws IOException {
        IncrementalIndexer indexer = builder().build();
        Path file = write("sub/a.txt", "alpha");

        assertEquals(List.of("sub/a.txt"), indexer.syncFile(root, file).added());
        assertEquals(List.of("sub/a.txt"), indexer.syncFile(root, Path.of("sub/a.txt")).unchanged());

        Files.delete(file);
        assertEquals(List.of("sub/a.txt"), indexer.syncFile(root, file).deleted());
        assertTrue(indexer.syncFile(root, file).deleted().isEmpty());
        assertTrue(index.docIds().isEmpty());
    }

    @Test
    void syncFileRejectsPathsOutsideRoot(@TempDir Path elsewhere) {
        IncrementalIndexer indexer = builder().build();

        assertThrows(IllegalArgumentException.class, () -> indexer.syncFile(root, elsewhere.resolve("x.txt")));
        assertThrows(IllegalArgumentException.class, () -> indexer.syncFile(root, Path.of("../x.txt")));
        assertThrows(IllegalArgumentException.class, () -> indexer.sync(root.resolve("missing")));
    }

    @Test
    void symlinkedRootIsIndexedNotWiped(@TempDir Path elsewhere) throws IOException {
        write("a.txt", "alpha");
        builder().build().sync(root);
        Path link = Files.createSymbolicLink(elsewhere.resolve("corpus-link"), root);

        SyncReport report = builder().build().sync(link);

        assertEquals(List.of("a.txt"), report.unchanged());
        assertTrue(report.deleted().isEmpty());
        assertEquals(Set.of("a.txt"), index.docIds());
    }

    @Test
    void syncFileSkipsSymlinksLikeSync(@TempDir Path elsewhere) throws IOException {
        Path target = Files.writeString(elsewhere.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(root.resolve("link.txt"), target);
        Files.createDirectories(elsewhere.resolve("dir"));
        Files.writeString(elsewhere.resolve("dir/inner.txt"), "inner");
        Files.createSymbolicLink(root.resolve("linkdir"), elsewhere.resolve("dir"));
        IncrementalIndexer indexer = builder().build();

        assertTrue(indexer.sync(root).added().isEmpty());
        assertTrue(indexer.syncFile(root, Path.of("link.txt")).added().isEmpty());
        assertTrue(indexer.syncFile(root, Path.of("linkdir/inner.txt")).added().isEmpty());
        assertTrue(index.docIds().isEmpty());
    }

    @Test
    void storedHashIsOfTheBytesActuallyLoaded() throws IOException {
        Path file = write("a.txt", "version A");
        DocumentLoader racing = new ParagraphLoader() {
            boolean raced;

            @Override
            public ParsedDocument load(Path f, String docId) throws IOException {
                if (!raced) {
                    raced = true;
                    Files.writeString(f, "version B");
                }
                return super.load(f, docId);
            }
        };
        IncrementalIndexer indexer = builder().loader(racing).build();
        indexer.sync(root);
        Files.writeString(file, "version A");

        SyncReport report = indexer.sync(root);

        assertEquals(List.of("a.txt"), report.updated());
        assertEquals("version A", index.get("a.txt#0").orElseThrow().text());
    }

    @Test
    void fileVanishingDuringSyncIsDeletedNotFailed() throws IOException {
        write("a.txt", "alpha");
        write("b.txt", "bravo");
        IncrementalIndexer indexer = builder().build();
        indexer.sync(root);
        write("a.txt", "alpha changed");
        DocumentLoader vanishing = new ParagraphLoader() {
            @Override
            public ParsedDocument load(Path f, String docId) throws IOException {
                Files.delete(f);
                throw new NoSuchFileException(f.toString());
            }
        };

        SyncReport report = builder().loader(vanishing).build().sync(root);

        assertEquals(List.of("a.txt"), report.deleted());
        assertTrue(report.failed().isEmpty());
        assertEquals(List.of("b.txt"), report.unchanged());
        assertEquals(Set.of("b.txt"), index.docIds());
    }

    @Test
    void fullSyncCommitsOnceNotPerDocument(@TempDir Path indexDir) throws IOException {
        for (int i = 0; i < 5; i++) {
            write("doc" + i + ".txt", "content " + i);
        }
        index.close();
        index = LuceneChunkIndex.open(indexDir, DIMS);
        long before = commitGeneration(indexDir);

        SyncReport report = builder().build().sync(root);

        assertEquals(5, report.added().size());
        assertEquals(before + 1, commitGeneration(indexDir));
        assertEquals(5, index.docIds().size());
    }

    private static long commitGeneration(Path dir) throws IOException {
        try (Directory d = FSDirectory.open(dir)) {
            return SegmentInfos.readLatestCommit(d).getGeneration();
        }
    }

    @Test
    void builderValidatesArguments() {
        assertThrows(NullPointerException.class, () -> IncrementalIndexer.builder().build());
        assertThrows(IllegalArgumentException.class, () -> IncrementalIndexer.builder().embedBatchSize(0));
        assertThrows(NullPointerException.class, () -> IncrementalIndexer.builder().include(null));
    }

    /** Supports {@code *.txt}; one paragraph block per blank-line-separated section. */
    private static class ParagraphLoader implements DocumentLoader {
        @Override
        public boolean supports(Path file) {
            return file.getFileName().toString().endsWith(".txt");
        }

        @Override
        public ParsedDocument load(Path file, String docId) throws IOException {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.contains("BROKEN")) {
                throw new IOException("cannot parse " + docId);
            }
            List<DocumentBlock> blocks = new ArrayList<>();
            int line = 1;
            for (String para : content.split("\n\n")) {
                if (!para.isBlank()) {
                    blocks.add(new DocumentBlock(BlockType.PARAGRAPH, para, 0, List.of(), line, line, null));
                }
                line += para.split("\n", -1).length + 1;
            }
            return new ParsedDocument(docId, docId, file.toString(),
                    IncrementalIndexer.sha256(Files.readAllBytes(file)), blocks, Map.of());
        }
    }

    private static final class BlockChunker implements Chunker {
        @Override
        public List<Chunk> chunk(ParsedDocument document) {
            List<Chunk> chunks = new ArrayList<>();
            for (DocumentBlock block : document.blocks()) {
                int ordinal = chunks.size();
                chunks.add(new Chunk(document.docId() + "#" + ordinal, document.docId(), document.title(),
                        document.source(), block.sectionPath(), block.text(), block.startLine(), block.endLine(),
                        block.page(), document.metadata()));
            }
            return chunks;
        }
    }
}
