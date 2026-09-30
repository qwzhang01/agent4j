package io.github.qwzhang01.agent.rag.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loader output: a document broken into ordered structural blocks.
 *
 * @param docId       stable identity; by convention the corpus-relative path with {@code /} separators
 * @param title       document title (first H1, PDF title, or file name)
 * @param source      where it came from (absolute path or URI), for display and click-through
 * @param contentHash SHA-256 hex of the raw bytes; drives incremental re-indexing
 * @param blocks      structural blocks in reading order
 * @param metadata    free-form attributes (e.g. {@code version}, {@code product}); usable as retrieval filters
 */
public record ParsedDocument(
        String docId,
        String title,
        String source,
        String contentHash,
        List<DocumentBlock> blocks,
        Map<String, String> metadata
) {
    public ParsedDocument {
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(contentHash, "contentHash");
        title = title == null ? docId : title;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
